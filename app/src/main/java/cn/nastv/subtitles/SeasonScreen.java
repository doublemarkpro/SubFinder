package cn.nastv.subtitles;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The directory and queue share the same poster and native TV navigation. */
final class SeasonScreen {
    private static final Pattern EPISODE = Pattern.compile("(?i)(?:^| )(S(\\d{2})E\\d{2,3}(?:-E\\d{2,3})?)$");
    private static final Pattern SEASON_FOLDER = Pattern.compile("(?i)(?:(?:season|series|s)\\s*[._ -]*\\d{1,2}|第\\s*[0-9一二三四五六七八九十]+\\s*季)");
    private static final Pattern HOLIDAY_TITLE = Pattern.compile("(?i)^mistletoe[ ._-]+murders(?:[ ._-]*[\\[(]?(?:19|20)\\d{2}[\\])]?)?$");
    private static final Pattern FILE_MARKER = Pattern.compile("(?i)(?:S\\d{1,2}[ ._-]*E\\d{1,3}|\\d{1,2}x\\d{1,3}|第\\s*\\d{1,2}\\s*季\\s*第?\\s*\\d{1,3}\\s*集)(?!\\d)");
    private static final Pattern RELEASE = Pattern.compile("(?i)(?:^|[ ._\\[(\\-])(?:\\d{3,4}[pi]|4k|uhd|web(?:[ ._-]?(?:dl|rip))?|blu[ ._-]?ray|[bd]drip|brrip|dvd(?:rip)?|hdtv|hdr\\d*|dv|dovi|x26[45]|h[ ._-]?26[45]|hevc|av1|remux|aac|ddp?|dts|atmos|multi|dual|repack|proper|internal)(?![A-Za-z])");
    private static final int CHECKED_FILL = 0xff282e24;

    private static class Media {
        final NasStore.Entry entry;
        final String show, code, season, episodeTitle;
        Media(NasStore.Entry entry,String path) {
            this.entry=entry;
            String name=entry.directory?entry.name:SubtitleService.suggestQuery(entry.name);
            Matcher episode=EPISODE.matcher(name);
            if(!entry.directory && episode.find()){
                code=episode.group(1); season="第 "+Integer.parseInt(episode.group(2))+" 季";
                String prefix=name.substring(0,episode.start()).trim();
                show=prefix.isEmpty()?folderName(path,true):prefix;
                episodeTitle=titleFromFilename(entry.name,code);
            }else{
                show=name.isEmpty()?entry.name:name; code=""; season=""; episodeTitle="";
            }
        }
    }

    private static String titleFromFilename(String filename,String code){
        if(code.contains("-"))return "";
        int dot=filename.lastIndexOf('.');
        String stem=dot>0?filename.substring(0,dot):filename;
        Matcher marker=FILE_MARKER.matcher(stem);
        if(!marker.find())return "";
        String tail=stem.substring(marker.end());
        Matcher release=RELEASE.matcher(tail);
        if(release.find())tail=tail.substring(0,release.start());
        return tail.replace('.', ' ').replace('_',' ').replaceAll("^[\\s-]+|[\\s-]+$","").trim();
    }
    private static String folderName(String path,boolean skipSeason) {
        String[] parts=path.replace('\\','/').split("/");
        for(int i=parts.length-1;i>=0;i--){
            String part=parts[i].trim();
            if(!part.isEmpty() && (!skipSeason || !SEASON_FOLDER.matcher(part).matches()))return part;
        }
        return "";
    }

    private static class Folder {
        final String path;
        final List<NasStore.Entry> entries;
        final List<Media> media=new ArrayList<>();
        int videos, folders;
        boolean episodes=true, oneSeason=true;
        Media first;
        Folder(String path,List<NasStore.Entry> entries){
            this.path=path; this.entries=entries;
            for(NasStore.Entry entry:entries){
                Media item=new Media(entry,path);
                media.add(item);
                if(entry.directory){folders++; continue;}
                videos++; episodes &= !item.code.isEmpty();
                if(first==null)first=item;
                else if(!first.show.equalsIgnoreCase(item.show) || !first.season.equals(item.season))oneSeason=false;
            }
            episodes &= videos>0;
            oneSeason &= episodes && folders==0 && first!=null && !first.show.isEmpty();
        }
        String summary(){
            String video=videos+(episodes?" 集":" 部影片");
            if(folders==0)return videos==0?"":video;
            return folders+" 个文件夹"+(videos==0?"":"   ·   "+video);
        }
    }

    private static class Poster extends FrameLayout{
        final TvUi ui;
        final Jacket jacket;
        final ImageView still;
        final TextView title,meta;
        Poster(TvUi ui){
            super(ui.a); this.ui=ui;
            setBackground(ui.shape(0xff22382d,0,9)); setClipToOutline(true);
            jacket=new Jacket(ui); addView(jacket,new LayoutParams(-1,-1));
            still=new ImageView(ui.a); still.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(still,new LayoutParams(-1,-1));
            View shade=new View(ui.a);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x0008130f,0x1408130f,0xe808130f}));
            addView(shade,new LayoutParams(-1,-1));
            LinearLayout copy=ui.col();
            copy.setPadding(ui.dp(20),0,ui.dp(15),ui.dp(20));
            title=ui.text("",36,TvUi.PAPER,false); title.setTypeface(Typeface.SERIF);
            title.setLetterSpacing(-.035f); title.setLineSpacing(0,.94f); ui.clip(title,4);
            title.setTextColor(0xfffff6e4); copy.addView(title,ui.size(-1,-2)); ui.space(copy,11);
            meta=ui.text("",13,0xffe3e6d9,false); ui.clip(meta,2); copy.addView(meta);
            addView(copy,new LayoutParams(-1,-2,Gravity.BOTTOM));
        }

        void bind(Folder folder,Media item){
            if(folder.oneSeason)item=folder.first;
            String name=item==null?folderName(folder.path,false):item.show;
            if(name.isEmpty())name=folderName(folder.path,false);
            if(name.isEmpty())name="影片目录";
            boolean illustrated=HOLIDAY_TITLE.matcher(name.trim()).matches();
            if(illustrated){
                if(still.getDrawable()==null)still.setImageResource(R.drawable.winter_shop);
                still.setVisibility(VISIBLE);
            }else still.setVisibility(GONE);
            jacket.setVisibility(illustrated?GONE:VISIBLE);
            String caption;
            if(folder.oneSeason)caption=item.season+"   ·   "+folder.summary();
            else if(item==null)caption=folder.summary();
            else if(item.entry.directory)caption="文件夹";
            else if(!item.season.isEmpty())caption=item.season+"   ·   "+item.code;
            else caption=mediaDetails(item.entry);
            if(illustrated){
                int split=name.toLowerCase(Locale.ROOT).indexOf("murders");
                String lines=name.substring(0,split).trim()+"\n"+name.substring(split);
                SpannableString styled=new SpannableString(lines);
                styled.setSpan(new StyleSpan(Typeface.ITALIC),lines.indexOf('\n')+1,lines.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                title.setText(styled);
            }else title.setText(name);
            int large=ui.a.getResources().getConfiguration().screenWidthDp<840?32:40;
            title.setTextSize(name.length()>36?26:(name.length()>22?31:large));
            meta.setText(caption); meta.setVisibility(caption.isEmpty()?GONE:VISIBLE);
        }
    }

    /** A typographic jacket for folders and videos without local artwork. */
    private static class Jacket extends View{
        final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF arc=new RectF();
        Jacket(TvUi ui){
            super(ui.a);
            setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xff30473b,0xff20382d,0xff182f27}));
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float w=getWidth(),h=getHeight();
            pen.setStyle(Paint.Style.STROKE); pen.setStrokeWidth(Math.max(1,w/420f));
            pen.setColor(0x596f9176);
            float radius=Math.max(w*.84f,h*.38f);
            for(int i=0;i<10;i++){
                float r=radius+i*w*.042f;
                arc.set(w*1.02f-r,h*.02f-r,w*1.02f+r,h*.02f+r); c.drawArc(arc,0,360,false,pen);
            }
            pen.setColor(0x837e9475);
            c.drawLine(w*.07f,h*.065f,w*.19f,h*.065f,pen);
        }
    }

    private static class Workspace {
        final Folder folder;
        final Poster poster;
        final LinearLayout panel;
        Workspace(TvUi ui,Folder folder){
            this.folder=folder;
            LinearLayout layout=ui.row(); layout.setGravity(Gravity.TOP); layout.setBaselineAligned(false);
            ui.fill(ui.body,layout,-1);
            poster=new Poster(ui); poster.bind(folder,folder.oneSeason?folder.first:null);
            LinearLayout.LayoutParams cover=ui.size(0,-1); cover.weight=.65f; layout.addView(poster,cover);
            ui.space(layout,ui.a.getResources().getConfiguration().screenWidthDp<850?16:24);
            panel=ui.col(); LinearLayout.LayoutParams right=ui.size(0,-1); right.weight=1.4f; layout.addView(panel,right);
        }
    }

    static LinearLayout workspace(TvUi ui,String path,List<NasStore.Entry> entries){
        return new Workspace(ui,new Folder(path,entries)).panel;
    }

    private static String mediaDetails(NasStore.Entry entry){
        String name=entry.name; int dot=name.lastIndexOf('.');
        String extension=dot<0?"":name.substring(dot+1).toUpperCase(Locale.ROOT);
        long n=entry.size;
        String size=n>=1073741824L?String.format(Locale.ROOT,"%.1f GB",n/1073741824.0):
                n>=1048576L?String.format(Locale.ROOT,"%.0f MB",n/1048576.0):
                n>=1024L?String.format(Locale.ROOT,"%.0f KB",n/1024.0):n+" B";
        return extension.isEmpty()?size:extension+"   ·   "+size;
    }

    private static class FileRow extends LinearLayout {
        final TvUi.Glyph mark;
        final TextView code,title,detail,status;
        FileRow(TvUi ui){
            super(ui.a); setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL); ui.pad(this,9,0);
            setLayoutParams(new AbsListView.LayoutParams(-1,ui.dp(45)));
            mark=new TvUi.Glyph(ui.a,"film",TvUi.AMBER); addView(mark,ui.size(20,20)); ui.space(this,9);
            code=ui.text("",12,TvUi.PAPER,true); code.setTypeface(Typeface.MONOSPACE); addView(code);
            LinearLayout labels=ui.col(); labels.setPadding(ui.dp(7),0,0,0);
            title=ui.text("",15,TvUi.PAPER,true); ui.clip(title,1);
            detail=ui.text("",11,TvUi.MUTED,false); ui.clip(detail,1);
            labels.addView(title); ui.space(labels,2); labels.addView(detail); ui.fill(this,labels,-2);
            ui.space(this,7); status=ui.text("",12,TvUi.MUTED,false); status.setSingleLine(true); addView(status);
        }
        void bind(TvUi ui,Media item,boolean batch,boolean existing,boolean other,boolean queued,boolean chosen){
            NasStore.Entry e=item.entry;
            boolean locked=batch&&!e.directory&&(existing||queued);
            String icon=e.directory?"folder":batch?(existing||chosen?"check":queued?"clock":"blank"):"film";
            boolean checked=chosen&&batch&&!locked;
            mark.type=icon; mark.p.setColor(checked?TvUi.INK:existing?TvUi.GOOD:TvUi.AMBER); mark.invalidate();
            mark.setBackground(batch&&!e.directory?ui.shape(checked?TvUi.AMBER:0,checked?0:TvUi.LINE,4):null);
            setBackground(checked?ui.shape(CHECKED_FILL,0,5):null);
            code.setText(item.code); code.setVisibility(item.code.isEmpty()?GONE:VISIBLE);
            code.setTextColor(locked?TvUi.MUTED:TvUi.AMBER);
            String label=!item.episodeTitle.isEmpty()?item.episodeTitle:item.show.isEmpty()?e.name:item.show;
            title.setText(e.directory?e.name:label); title.setTextColor(locked?TvUi.MUTED:TvUi.PAPER);
            detail.setText(e.directory?"文件夹":e.name);
            status.setText(e.directory?"›":existing?"已有简中":queued?"已在队列":batch?"缺简中":other?"有同名字幕":mediaDetails(e));
            status.setTextColor(existing?TvUi.GOOD:queued?TvUi.AMBER:TvUi.MUTED);
            setContentDescription(e.name+"，"+status.getText()+(checked?"，已选":""));
        }
    }

    static ListView show(TvUi ui,String path,List<NasStore.Entry> entries,Set<String> existing,Set<String> other,
            Set<String> queued,Set<String> checked,boolean batch,TvUi.OnPick onRow,
            Runnable toggleBatch,Runnable toggleMissing,Runnable startBatch,Runnable openPack){
        View oldFocus=ui.body.findFocus();
        int oldPosition=oldFocus instanceof ListView?((ListView)oldFocus).getSelectedItemPosition():-1;
        int oldTop=0;
        if(oldPosition>=0){
            View selected=((ListView)oldFocus).getSelectedView();
            if(selected!=null)oldTop=selected.getTop()-((ListView)oldFocus).getListPaddingTop();
        }
        Object focusedTag=oldFocus==null?null:oldFocus.getTag();
        Object cached=ui.body.getTag();
        boolean preserveHeaderFocus=cached instanceof Folder && oldFocus==null && ui.a.getCurrentFocus()!=null;
        Folder folder=cached instanceof Folder && ((Folder)cached).path.equals(path)
                && ((Folder)cached).entries.equals(entries)?(Folder)cached:new Folder(path,entries);
        ui.body.removeAllViews(); ui.body.setTag(folder);
        Workspace work=new Workspace(ui,folder);
        LinearLayout panel=work.panel;
        boolean selectable=false,allChecked=true;
        int selectionCount=0;
        for(Media m:folder.media){
            if(m.entry.directory || existing.contains(m.entry.path)||queued.contains(m.entry.path))continue;
            selectable=true;
            if(checked.contains(m.entry.path))selectionCount++;
            else allChecked=false;
        }
        LinearLayout header=ui.row(); header.setBaselineAligned(false);
        LinearLayout heading=ui.col(); LinearLayout context=ui.row();
        context.addView(new TvUi.Glyph(ui.a,batch?"caption":"folder",TvUi.AMBER),ui.size(15,15)); ui.space(context,5);
        context.addView(ui.text(batch?"简体中文字幕":"NAS 文件夹",12,TvUi.AMBER,false)); heading.addView(context);
        ui.space(heading,2);
        heading.addView(ui.text(batch?(folder.episodes?"选择剧集":"选择影片"):(folder.episodes?"剧集目录":"影片目录"),25,TvUi.PAPER,true));
        ui.fill(header,heading,-2);
        TextView mode=ui.button(batch?"退出批量":"批量下载",!batch,toggleBatch); mode.setTag("batchMode");
        if(folder.videos>0)header.addView(mode,ui.size(-2,34));
        if(!batch && openPack!=null){
            ui.space(header,7);
            header.addView(ui.button("整季字幕包",false,openPack),ui.size(-2,34));
        }
        TextView all=ui.button(allChecked&&selectable?"取消全选":"全选缺字幕",false,toggleMissing);
        all.setTag("allMissing"); available(all,selectable);
        if(batch){ui.space(header,7); header.addView(all,ui.size(-2,34));}
        panel.addView(header,ui.size(-1,-2)); ui.space(panel,8);
        ListView list=ui.list(); list.setTag("filesList");
        BaseAdapter adapter=new BaseAdapter(){
            public int getCount(){return folder.media.size();}
            public Media getItem(int i){return folder.media.get(i);}
            public long getItemId(int i){return i;}
            @Override public boolean areAllItemsEnabled(){return !batch;}
            @Override public boolean isEnabled(int i){
                NasStore.Entry e=getItem(i).entry;
                return !batch||e.directory||(!existing.contains(e.path)&&!queued.contains(e.path));
            }
            public View getView(int i,View reuse,ViewGroup parent){
                FileRow row=reuse==null?new FileRow(ui):(FileRow)reuse;
                Media entry=getItem(i); String key=entry.entry.path;
                row.bind(ui,entry,batch,existing.contains(key),other.contains(key),queued.contains(key),checked.contains(key));
                return row;
            }
        };
        list.setAdapter(adapter); list.setOnItemClickListener((p,v,i,id)->{if(adapter.isEnabled(i))onRow.pick(i);});
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> p,View v,int i,long id){work.poster.bind(folder,folder.media.get(i));}
            public void onNothingSelected(AdapterView<?> p){}
        });
        boolean canFocusList=(batch?selectable||folder.folders>0:!entries.isEmpty());
        list.setFocusable(canFocusList); ui.fill(panel,list,-1); ui.rule(panel); ui.space(panel,7);
        LinearLayout actions=ui.row();
        actions.addView(new TvUi.Glyph(ui.a,batch?"folder":"caption",TvUi.MUTED),ui.size(17,17)); ui.space(actions,6);
        String batchHint=selectionCount>0?"按 → 前往下载  ·  保存到影片目录":"保存到各影片所在目录";
        TextView hint=ui.text(batch?batchHint:folder.videos>0?"选择影片，查看可用字幕":"选择文件夹以浏览影片",12,TvUi.MUTED,false);
        ui.clip(hint,2); ui.fill(actions,hint,-2);
        if(batch){
            ui.space(actions,7); TextView start=ui.button("下载所选 "+selectionCount+(folder.episodes?" 集":" 部"),true,startBatch);
            start.setTag("startBatch"); available(start,selectionCount>0); actions.addView(start,ui.size(-2,39));
            if(selectionCount>0){
                int[] anchor={oldPosition,oldTop};
                shortcut(list,KeyEvent.KEYCODE_DPAD_RIGHT,()->{
                    anchor[0]=list.getSelectedItemPosition();
                    View selected=list.getSelectedView();
                    anchor[1]=selected==null?0:selected.getTop()-list.getListPaddingTop();
                    start.requestFocus();
                });
                shortcut(all,KeyEvent.KEYCODE_DPAD_DOWN,start::requestFocus);
                shortcut(start,KeyEvent.KEYCODE_DPAD_LEFT,()->{
                    list.requestFocus();
                    if(anchor[0]>=0 && anchor[0]<entries.size() && adapter.isEnabled(anchor[0]))
                        list.setSelectionFromTop(anchor[0],anchor[1]);
                });
            }
        }
        panel.addView(actions,ui.size(-1,batch?39:27));
        if(preserveHeaderFocus)return list;
        View restore=focusedTag==null?null:ui.body.findViewWithTag(focusedTag);
        if(restore!=null && restore!=list && restore.isEnabled() && restore.isFocusable())restore.requestFocus();
        else if(canFocusList){
            list.requestFocus(); if(oldPosition>=0&&oldPosition<entries.size()&&adapter.isEnabled(oldPosition))
                list.setSelectionFromTop(oldPosition,oldTop);
        }else if(folder.videos>0)mode.requestFocus();
        return list;
    }

    private static void available(View control,boolean enabled){
        control.setEnabled(enabled); control.setFocusable(enabled); control.setAlpha(enabled?1f:.45f);
    }
    private static void shortcut(View from,int direction,Runnable move){
        from.setOnKeyListener((view,key,event)->{
            if(key!=direction)return false;
            if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)move.run();
            return true;
        });
    }
    private SeasonScreen(){}
}
