package cn.nastv.subtitles;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** One user-chosen pack, scoped to a NAS login, share, series, season and folder. */
final class PackFlow {
    private enum Page { SOURCES, NAS, READING, PREVIEW, SAVING, RESULT }
    private final Activity activity;
    private final TvUi ui;
    private final LinearLayout body;
    private final NasStore login;
    private final BatchQueue queue;
    private final ExecutorService nasWorker, parser;
    private final String identity,share,folder,show,anchor,originalShow;
    private final int season;
    private final Runnable exit;
    private final Map<String,Target> targets=new LinkedHashMap<>();
    private NasStore store;
    private SeasonPack pack;
    private SubHdPicker picker;
    private String label,sourceName,localFolder;
    private Page page;
    private boolean closed;
    private int revision;
    private Future<?> pending;

    private static final class Target {
        final NasStore.Entry video;
        final String query;
        final boolean existing;
        List<SeasonPack.Track> choices=Collections.emptyList();
        Target(NasStore.Entry video,String query,boolean existing){
            this.video=video;this.query=query;this.existing=existing;
        }
    }

    PackFlow(Activity a,TvUi ui,NasStore login,BatchQueue queue,ExecutorService nasWorker,ExecutorService parser,
             String share,String anchor,String term,Runnable exit){
        this.activity=a;this.ui=ui;body=ui.body;this.login=login;this.queue=queue;
        this.nasWorker=nasWorker;this.parser=parser;this.share=share;this.anchor=anchor;this.exit=exit;
        identity=queue.identity();
        String lookup=anchorQuery(term,anchor);
        show=SeasonPack.showOf(lookup);season=SeasonPack.seasonOf(lookup);
        originalShow=SeasonPack.showOf(MainActivity.queryForPath(anchor));
        String current=parent(anchor),release=last(current);
        // Release folders are often siblings in a season, each containing just one episode's MKV.
        folder=SeasonPack.seasonOf(release)==season && (sameShow(SeasonPack.showOf(release),show)
                || release.matches("(?i)S\\d{1,2}E\\d{1,3}")) ? parent(current):current;
        localFolder=folder;
    }

    private static String parent(String s){int i=s.lastIndexOf('/');return i<0?"":s.substring(0,i);}
    private static String last(String s){return s.substring(s.lastIndexOf('/')+1);}
    private static String key(String s){return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
    private static boolean sameShow(String a,String b){return !a.isEmpty() && !b.isEmpty() && key(a).equals(key(b));}
    private static String anchorQuery(String term,String path){
        String parsed=MainActivity.queryForPath(path);
        if(SeasonPack.seasonOf(term)>=0 && !SeasonPack.showOf(term).isEmpty())return term;
        String episode=SeasonPack.episodeOf(parsed);
        if(!episode.isEmpty() && !term.trim().isEmpty() && SeasonPack.seasonOf(term)<0)
            return term.trim()+" "+episode;
        return parsed;
    }
    static boolean supports(String term,String path){
        String canonical=anchorQuery(term,path);
        return SeasonPack.seasonOf(canonical)>=0 && !SeasonPack.showOf(canonical).isEmpty();
    }
    boolean isSaving(){return page==Page.SAVING;}
    void start(boolean openOnline){
        loading("正在读取同季影片…");
        work(nasWorker,()->{
            store=login.viewOfShare(share);
            List<Target> found=new ArrayList<>();
            List<NasStore.Entry> root=store.list(folder);
            addTargets(found,root);
            for(NasStore.Entry entry:root){
                if(!entry.directory || entry.name.startsWith("."))continue;
                if(entry.path.equals(parent(anchor)) || episodeFolder(entry.name))addTargets(found,store.list(entry.path));
            }
            found.sort(Comparator.comparing(t->t.video.path,String.CASE_INSENSITIVE_ORDER));
            return found;
        },found->{
            targets.clear();
            for(Target target:found)targets.put(target.video.path,target);
            if(targets.isEmpty()){notice("当前目录已找不到同剧、同季的影片。",exit);return;}
            sources();
            if(openOnline)online();
        },e->notice(message(e),()->start(openOnline)));
    }
    private boolean episodeFolder(String name){
        String parsed=SubtitleService.suggestQuery(name);
        if(SeasonPack.seasonOf(parsed)!=season)return false;
        String series=SeasonPack.showOf(parsed);
        return sameShow(series,show) || sameShow(series,originalShow) ||
                (series.isEmpty() && !folder.isEmpty() && sameShow(last(parent(folder)),show));
    }
    private void addTargets(List<Target> out,List<NasStore.Entry> entries){
        Set<String> existing=BatchQueue.simplifiedPaths(entries);
        for(NasStore.Entry video:entries){
            if(!video.isVideo())continue;
            String query=MainActivity.queryForPath(video.path);
            String episode=SeasonPack.episodeOf(query);
            if(episode.isEmpty() || SeasonPack.seasonOf(query)!=season)continue;
            String series=SeasonPack.showOf(query);
            if(!video.path.equals(anchor) && !sameShow(series,show) && !sameShow(series,originalShow))continue;
            out.add(new Target(video,show+" "+episode,existing.contains(video.path)));
        }
    }
    private void sources(){
        change(Page.SOURCES);pack=null;
        SeasonPackScreen.sources(ui,show+" · 第 "+season+" 季",
                Collections.singletonList(new TvUi.Item("在 SubHD 选整季字幕包","查找这部剧的简中或双语合集","caption")),
                i->online(),()->archives(folder),null,"");
    }
    private void online(){
        if(picker!=null)picker.close();
        final int stamp=revision;
        picker=SubHdPicker.open(activity,show,season,(data,name,description,aliases)->{
            if(!active(stamp))return;
            if(data==null || data.length==0 || data.length>SeasonPack.MAX_DOWNLOAD){
                notice("收到的字幕包为空或超过大小限制。",this::sources);return;
            }
            label=description;sourceName="SubHD";
            loading("正在解包并匹配季、集号…");
            List<String> names=new ArrayList<>(aliases);names.add(show);
            work(parser,()->readPack(names,false,f->f.write(data)),result->{pack=result;preview();},
                    e->notice(message(e),this::sources));
        });
    }
    private interface WriteArchive {void write(FileOutputStream out) throws IOException;}
    private SeasonPack readPack(List<String> aliases,boolean requireExplicitSeason,WriteArchive write) throws IOException {
        File temp=File.createTempFile("season-pack-",".bin",activity.getCacheDir());
        try {
            try(FileOutputStream out=new FileOutputStream(temp)){write.write(out);}
            return SeasonPack.read(temp,season,aliases,requireExplicitSeason);
        }finally{if(!temp.delete())temp.deleteOnExit();}
    }
    private void archives(String path){
        change(Page.NAS);localFolder=path;
        body.removeAllViews();body.addView(ui.note("正在读取字幕包目录…"));
        work(nasWorker,()->store.list(path),entries->{
            body.removeAllViews();body.addView(ui.note(share+(path.isEmpty()?"":" / "+path)));ui.space(body,8);
            List<TvUi.Item> rows=new ArrayList<>();List<NasStore.Entry> visible=new ArrayList<>();
            if(!path.isEmpty())rows.add(new TvUi.Item("上一级目录","","folder"));
            for(NasStore.Entry entry:entries){
                if(entry.name.startsWith(".") || (!entry.directory && !entry.name.matches("(?i).+\\.(zip|rar|7z)")))continue;
                visible.add(entry);
                rows.add(new TvUi.Item(entry.name,entry.directory?"文件夹":"字幕包",entry.directory?"folder":"caption"));
            }
            if(!rows.isEmpty()){
                ListView list=ui.items(rows,i->{
                    if(!path.isEmpty() && i==0){archives(parent(path));return;}
                    NasStore.Entry entry=visible.get(path.isEmpty()?i:i-1);
                    if(entry.directory)archives(entry.path);else openArchive(entry);
                });ui.fill(body,list,-1);list.requestFocus();
            }else body.addView(ui.note("此目录中没有字幕包。"));
            ui.space(body,8);TextView back=ui.button("返回字幕包来源",false,this::sources);
            body.addView(back,ui.size(-2,38));if(rows.isEmpty())back.requestFocus();
        },e->notice(message(e),()->archives(path)));
    }
    private void openArchive(NasStore.Entry entry){
        int declaredSeason=SeasonPack.declaredSeason(entry.name);
        if(declaredSeason>=0 && declaredSeason!=season){
            notice("这个字幕包标的是第 "+declaredSeason+" 季，当前影片是第 "+season+" 季。",()->archives(localFolder));
            return;
        }
        loading("正在从 NAS 读取字幕包…");sourceName="NAS 字幕包";label=entry.name;
        work(nasWorker,()->readPack(Collections.singletonList(show),declaredSeason == -2,
                        out->store.copyArchive(entry,out,SeasonPack.MAX_DOWNLOAD)),
                result->{
                    pack=result;
                    if(anyMatched() || pack.availableSeries().isEmpty()){preview();return;}
                    List<String> aliases=pack.availableSeries();
                    sourcesForAlias(result,aliases);
                },e->notice(message(e),()->archives(localFolder)));
    }
    private void sourcesForAlias(SeasonPack result,List<String> names){
        change(Page.PREVIEW);
        body.removeAllViews();
        body.addView(ui.text("包内哪个剧名对应 "+show+"？",20,TvUi.PAPER,true));ui.space(body,8);
        List<TvUi.Item> rows=new ArrayList<>();
        for(String name:names)rows.add(new TvUi.Item(name,"仅匹配第 "+season+" 季","caption"));
        ListView list=ui.items(rows,i->{pack=result.withAlias(names.get(i));preview();});
        ui.fill(body,list,-1);list.requestFocus();
        ui.space(body,8);body.addView(ui.button("改选字幕包",false,this::sources),ui.size(-2,38));
    }
    private boolean anyMatched(){
        for(Target t:targets.values())if(!pack.choices(t.query).isEmpty())return true;
        return false;
    }
    private void preview(){
        change(Page.PREVIEW);
        List<SeasonPackScreen.Row> rows=new ArrayList<>();
        for(Target t:targets.values()){
            t.choices=pack.choices(t.query);
            List<String> names=new ArrayList<>();
            for(SeasonPack.Track track:t.choices)names.add(track.path);
            String blocked=t.existing?"已有简中":queue.processing(identity,share,t.video.path)?"队列正在处理":"";
            rows.add(new SeasonPackScreen.Row(t.video.path,SeasonPack.episodeOf(t.query),names,blocked));
        }
        SeasonPackScreen.preview(ui,label,rows,this::save,this::sources);
    }
    private void save(Map<String,Integer> chosen){
        if(page!=Page.PREVIEW || chosen.isEmpty())return;
        change(Page.SAVING);final int stamp=revision;
        Map<String,Integer> selected=new LinkedHashMap<>(chosen);
        SeasonPackScreen.progress(ui,0,selected.size(),"正在复核影片和已有字幕…");
        nasWorker.execute(()->{
            List<TvUi.Item> outcomes=new ArrayList<>();
            int saved=0;
            for(Map.Entry<String,Integer> choice:selected.entrySet()){
                Target t=targets.get(choice.getKey());
                if(t==null)continue;
                String detail,icon;
                try{
                    String result=saveTrack(t,t.choices.get(choice.getValue()));
                    if(result==null){detail="已跳过 · 同名简中字幕已存在";icon="caption";}
                    else{detail="已保存 · "+share+"/"+result;icon="check";saved++;}
                }catch(Exception e){detail=message(e);icon="caption";}
                outcomes.add(new TvUi.Item(SeasonPack.episodeOf(t.query)+"  "+t.video.name,detail,icon));
                final int done=outcomes.size();
                activity.runOnUiThread(()->{if(active(stamp))SeasonPackScreen.progress(ui,done,selected.size(),t.video.name);});
            }
            final int count=saved;
            activity.runOnUiThread(()->{
                if(!active(stamp))return;
                change(Page.RESULT);
                SeasonPackScreen.results(ui,outcomes,exit);
                android.widget.Toast.makeText(activity,"已保存 "+count+" 份字幕",android.widget.Toast.LENGTH_SHORT).show();
            });
        });
    }
    private String saveTrack(Target t,SeasonPack.Track track) throws IOException {
        if(queue.processing(identity,share,t.video.path))throw new IOException("已跳过 · 另一项下载正在处理这部影片");
        List<NasStore.Entry> fresh=store.list(parent(t.video.path));
        boolean present=false;
        for(NasStore.Entry entry:fresh){
            if(entry.path.equals(t.video.path) && entry.isVideo() && entry.size==t.video.size){present=true;break;}
        }
        if(!present)throw new IOException("已跳过 · 影片已移动或被替换");
        if(BatchQueue.simplifiedPaths(fresh).contains(t.video.path))return null;
        String name=store.saveBatchSubtitle(t.video.path,track.extension,track.bytes);
        if(name!=null)queue.recordPackSave(identity,share,t.video,t.query,sourceName+" · "+last(name));
        return name;
    }
    private boolean active(int stamp){return !closed && revision==stamp && body==ui.body && !activity.isDestroyed();}
    private void change(Page next){
        revision++;page=next;
        if(pending!=null){pending.cancel(true);pending=null;}
    }
    private <T> void work(ExecutorService pool,Callable<T> task,Consumer<T> done,Consumer<Exception> failed){
        final int stamp=revision;
        pending=pool.submit(()->{
            try{
                T result=task.call();
                activity.runOnUiThread(()->{if(active(stamp)){pending=null;done.accept(result);}});
            }catch(Exception e){
                activity.runOnUiThread(()->{if(active(stamp)){pending=null;failed.accept(e);}});
            }
        });
    }
    private static String message(Exception e){return e.getMessage()==null?"操作失败，请重试。":e.getMessage();}
    private void loading(String text){change(Page.READING);body.removeAllViews();body.addView(ui.note(text));}
    private void notice(String text,Runnable retry){
        body.removeAllViews();body.addView(ui.note(text));ui.space(body,12);
        TextView button=ui.button("继续",true,retry);body.addView(button,ui.size(-2,38));button.requestFocus();
    }
    void back(){
        if(page==Page.SAVING)return;
        if(page==Page.RESULT || page==Page.SOURCES || store==null)exit.run();
        else if(page==Page.NAS && !localFolder.equals(folder) && !localFolder.isEmpty())archives(parent(localFolder));
        else sources();
    }
    void close(){
        closed=true;revision++;
        if(pending!=null)pending.cancel(true);
        if(picker!=null)picker.close();
    }
}
