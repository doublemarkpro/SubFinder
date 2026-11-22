package cn.nastv.subtitles;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private enum Screen { CONNECT, SHARES, OPENING, FILES, SUBTITLES, SAVED, QUEUE, PACK }
    private static final String[] LANG_NAMES={"简体中文","繁體中文","中英双语","英语"};
    private static final String[] LANG_CODES={"zh-cn","zh-tw","ze","en"};
    private static final String[] FILE_TAGS={"zh-CN","zh-TW","zh","en"};
    private final ExecutorService nasWorker=Executors.newSingleThreadExecutor(), http=Executors.newFixedThreadPool(2);
    private final SubtitleService service=new SubtitleService();
    private Future<?> pendingSearch;
    private final Map<String,Integer> positions=new HashMap<>();
    private SharedPreferences prefs;
    private RecentFolders folders;
    private TvUi ui;
    private Screen screen;
    private NasStore nas;
    private ShareAddress source;
    private String nasAccount="";
    private DiscoveryPicker discoveryPicker;
    private BatchQueue queue;
    private QueueScreen queuePane;
    private PackFlow pack;
    private final Runnable queueListener=this::queueChanged;
    private final Set<String> checkedFiles=new HashSet<>();
    private Set<String> visiblePending=Collections.emptySet(), existingSimplified=Collections.emptySet();
    private final Set<String> otherTracks=new HashSet<>();
    private List<NasStore.Entry> visibleFiles=Collections.emptyList();
    private boolean batchMode=false;
    private String shareName="", directory="", query="", saved="";
    private int sharePosition=0;
    private NasStore.Entry film;
    private int version=0,language=0,selectedResult=0;
    private boolean saving=false, connecting=false;
    private boolean backToFolderFromSettings;
    private List<NasStore.Entry> listing=Collections.emptyList();
    private List<SubtitleService.Result> matches=Collections.emptyList();
    private ListView fileList;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        ui=new TvUi(this); prefs=getSharedPreferences("settings",MODE_PRIVATE);
        folders=new RecentFolders(prefs);
        queue=BatchQueue.get(this);
        queue.setListener(queueListener);
        language=Math.max(0,Math.min(3,prefs.getInt("language",0))); connectionForm(true);
    }

    private void shell(Screen page,String title,String sub) {
        if(page!=Screen.PACK && pack!=null){pack.close();pack=null;}
        if(page!=Screen.SUBTITLES)cancelSearch();
        screen=page; fileList=null; queuePane=null;
        ui.navigation(queue.size(),page==Screen.QUEUE,()->{if(!writing() && screen!=Screen.QUEUE)showQueue();});
        ui.shell(title,sub,page==Screen.CONNECT,source==null?null:source.host,
                ()->{
                    if(writing()||nas==null||screen==Screen.SHARES||screen==Screen.OPENING)return;
                    if(screen==Screen.QUEUE){returnFromQueue();return;}
                    if(shareName.isEmpty()||screen==Screen.FILES||screen==Screen.CONNECT)loadShares();else browse(directory);
                },
                ()->{
                    if(writing() || screen==Screen.CONNECT)return;
                    boolean inLibrary=nas!=null && !shareName.isEmpty();
                    connectionForm();
                    backToFolderFromSettings=inLibrary;
                });
    }

    private boolean writing(){return saving || (pack!=null && pack.isSaving());}

    private void connectionForm() { connectionForm(false); }

    private void connectionForm(boolean coldStart) {
        version++; connecting=false;backToFolderFromSettings=false;
        shell(Screen.CONNECT,"","");
        int plain=InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        String previous=prefs.getString("host","");
        ShareAddress savedServer=null;
        if(!previous.isEmpty()){
            try {savedServer=ShareAddress.parse(previous,"");}catch(IllegalArgumentException ignored){}
        }
        String endpoint=savedServer==null?"":savedServer.endpoint();
        String savedAccount=prefs.getString("user","");
        ShareAddress recent=folders.get(savedServer,savedAccount,true);
        EditText host=ui.edit("IP 地址或主机名",endpoint,plain|InputType.TYPE_TEXT_VARIATION_URI);
        EditText user=ui.edit("访客可留空",savedAccount,plain);
        EditText password=ui.edit("NAS 账户密码",PasswordVault.read(prefs),
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        protectStoredPassword(host,user,password);
        CheckBox remember=new CheckBox(this);
        remember.setChecked(prefs.contains("secret"));
        TextView status=ui.note("");
        status.setVisibility(View.GONE);
        boolean sameSession=nas!=null && source!=null && source.endpoint().equalsIgnoreCase(endpoint)
                && nasAccount.equals(savedAccount);
        boolean passwordless=savedAccount.isEmpty() || prefs.getBoolean("passwordless",false);
        boolean canResume=sameSession || password.length()>0 || passwordless;
        Runnable resume=()->{
            if(connecting || recent==null)return;
            if(sameSession){openShare(recent,true);return;}
            if(!endpoint.equalsIgnoreCase(host.getText().toString().trim()) ||
                    !savedAccount.equals(user.getText().toString().trim())){
                host.setText(endpoint);user.setText(savedAccount);password.setText(PasswordVault.read(prefs));
                protectStoredPassword(host,user,password);
            }
            if(!passwordless && password.length()==0){
                entryStatus(status,"输入这个 NAS 账号的密码，即可继续打开上次目录。");
                password.requestFocus();return;
            }
            submitLogin(host,user,password,remember,status,recent);
        };
        Runnable discover=()->{
            if(connecting)return;
            discoveryPicker=new DiscoveryPicker(this,ui,address->{
                if(!host.getText().toString().trim().equalsIgnoreCase(address)){
                    password.setText("");host.setText(address);
                }
                (user.length()>0?password:user).requestFocus();
            },host::requestFocus);
            discoveryPicker.show();
        };
        String path=recent==null?"":recent.share+(recent.folder.isEmpty()?"":"/"+recent.folder);
        TextView connect=EntryScreen.connection(ui,path,endpoint,canResume,host,user,password,remember,status,
                resume,discover,()->submitLogin(host,user,password,remember,status,null));
        password.setImeOptions(EditorInfo.IME_ACTION_DONE);
        password.setOnEditorActionListener((v,id,event)->{
            if(id==EditorInfo.IME_ACTION_DONE){connect.requestFocus();return true;}return false;
        });
        if(coldStart && canResume && folders.get(savedServer,savedAccount,false)!=null)resume.run();
    }

    private static void protectStoredPassword(EditText host,EditText user,EditText password) {
        if(password.length()==0)return;
        TextWatcher changed=new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                password.setText("");host.removeTextChangedListener(this);user.removeTextChangedListener(this);
            }
            public void afterTextChanged(Editable e){}
        };
        host.addTextChangedListener(changed);user.addTextChangedListener(changed);
    }

    private static void entryStatus(TextView status,String text){
        status.setText(text);status.setVisibility(View.VISIBLE);
    }

    private void submitLogin(EditText host,EditText user,EditText password,CheckBox remember,TextView status,
                             ShareAddress requestedFolder) {
        if(connecting)return;
        ShareAddress addr;
        try {addr=ShareAddress.parse(host.getText().toString().trim(),"");}
        catch(IllegalArgumentException e){entryStatus(status,e.getMessage());host.requestFocus();return;}
        String account=user.getText().toString().trim(),secret=password.getText().toString();
        if(account.isEmpty()&&!secret.isEmpty()){
            entryStatus(status,"已填写密码，请同时填写 NAS 用户名。");user.requestFocus();return;
        }
        boolean persist=remember.isChecked();
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(host.getWindowToken(),0);
        connecting=true;int ticket=++version;
        entryStatus(status,"正在连接 "+addr.endpoint()+"…");
        int cut=account.indexOf('\\');
        String domain=cut<0?"":account.substring(0,cut),login=cut<0?account:account.substring(cut+1);
        nasWorker.execute(()->{
            try{
                NasStore connected=NasStore.login(addr.host,addr.port,domain,login,secret);
                runOnUiThread(()->{
                    if(!active(ticket)){closeLater(connected);return;}
                    connecting=false;closeLater(nas);nas=connected;source=addr;nasAccount=account;
                    shareName="";directory="";resetSelection();sharePosition=0;positions.clear();
                    SharedPreferences.Editor edit=prefs.edit().putString("host",addr.endpoint()).putString("user",account)
                            .putBoolean("passwordless",secret.isEmpty());
                    try{PasswordVault.store(edit,persist?secret:"");}
                    catch(Exception ex){edit.remove("iv").remove("secret");toast("已连接。此设备暂时无法记住密码。");}
                    edit.apply();
                    queue.connected(addr.host,addr.port,domain,login,secret);
                    ShareAddress last=requestedFolder!=null?requestedFolder:folders.get(addr,account,true);
                    if(!addr.share.isEmpty())openShare(addr);
                    else if(last!=null)openShare(last,true);
                    else loadShares();
                });
            }catch(Exception e){
                runOnUiThread(()->{if(active(ticket)){
                    connecting=false;entryStatus(status,message(e));
                }});
            }
        });
    }

    private void loadShares(){
        int ticket=++version;NasStore store=nas;
        resetSelection();
        shareName="";directory="";positions.clear();
        shell(Screen.SHARES,"选择共享",source.endpoint());
        busy("正在读取 NAS 发布的 SMB 共享…");
        job(nasWorker,ticket,store::listShares,names->showShares(names,null),
                error->showShares(Collections.emptyList(),message(error)));
    }

    private void showShares(List<String> names,String error){
        shell(Screen.SHARES,"","");
        EntryScreen.shares(ui,source.endpoint(),names,error,sharePosition,i->{
            sharePosition=i;
            openShare(ShareAddress.parse(source.endpoint(),names.get(i)));
        },this::manualShare,this::loadShares);
    }

    private void manualShare(){
        ShareAddress last=folders.get(source,nasAccount,true);
        String recent=last==null?"":last.share+(last.folder.isEmpty()?"":"/"+last.folder);
        EditText entry=ui.edit("共享名 或 共享名/子目录",recent,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout panel=ui.col();ui.pad(panel,20,8);
        panel.addView(ui.note("以 Windows 中打开 \\\\NAS 后看到的第一层名称开头，例如 SATA存储11/TVSeries。"));
        ui.space(panel,8);panel.addView(entry,ui.size(-1,42));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("打开共享路径").setView(panel)
                .setPositiveButton("打开",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->{
            View open=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            open.setOnClickListener(v->{
                try{
                    ShareAddress path=ShareAddress.parse(source.endpoint(),entry.getText().toString());
                    if(path.share.isEmpty())throw new IllegalArgumentException("请填写共享名。");
                    dialog.dismiss();openShare(path);
                }catch(IllegalArgumentException ex){entry.setError(ex.getMessage());}
            });
            entry.setImeOptions(EditorInfo.IME_ACTION_GO);
            entry.setOnEditorActionListener((v,id,event)->{if(id==EditorInfo.IME_ACTION_GO){open.performClick();return true;}return false;});
            entry.requestFocus();
        });dialog.show();
    }

    private void openShare(ShareAddress target){openShare(target,false);}

    private void openShare(ShareAddress target,boolean returning){
        int ticket=++version;NasStore store=nas;
        shell(Screen.OPENING,returning?"继续浏览":"打开共享",target.share+(target.folder.isEmpty()?"":"  /  "+target.folder));
        busy("正在读取文件夹…");
        job(nasWorker,ticket,()->{
            store.selectShare(target.share);
            return store.list(target.folder);
        },entries->{
            resetSelection();
            shareName=target.share;directory=target.folder;positions.clear();listing=entries;
            folders.remember(source,nasAccount,shareName,directory);
            drawFiles();
        },error->{
            problem(returning?"上次的目录暂时无法打开":"无法打开此共享",error,()->openShare(target,returning));ui.space(ui.body,8);
            action(returning?"重新选择目录":"返回共享列表",this::loadShares).requestFocus();
        });
    }

    private void browse(String path) {browse(path,true);}

    private void browse(String path,boolean remember) {
        if(!directory.equals(path))resetSelection();
        int ticket=++version;directory=path;NasStore store=nas;
        shell(Screen.FILES,"影片目录",displayPath(path));busy("正在读取文件夹…");
        job(nasWorker,ticket,()->store.list(path),data->{
                    listing=data;
                    if(remember)folders.remember(source,nasAccount,shareName,path);
                    drawFiles();
                },
                e->problem("读取失败",e,()->browse(path)));
    }
    private String displayPath(String path) {
        return shareName+(path.isEmpty()?"":"  /  "+path.replace("/","  /  "));
    }
    private static String base(String s){return s.substring(s.lastIndexOf('/')+1);}
    private static String parent(String s){int i=s.lastIndexOf('/');return i<0?"":s.substring(0,i);}
    private void resetSelection() {
        batchMode=false;
        checkedFiles.clear();
        visibleFiles=Collections.emptyList();
        visiblePending=Collections.emptySet();
    }

    private void indexFolder() {
        visibleFiles=new ArrayList<>();
        for(NasStore.Entry entry:listing){
            if(!entry.name.startsWith(".") && (entry.directory || entry.isVideo()))visibleFiles.add(entry);
        }
        otherTracks.clear();
        existingSimplified=BatchQueue.simplifiedPaths(listing,otherTracks);
        visiblePending=queue.unfinishedPaths(shareName);
        checkedFiles.retainAll(missingFiles());
    }

    private Set<String> missingFiles() {
        Set<String> missing=new HashSet<>();
        for(NasStore.Entry entry:visibleFiles){
            if(!entry.directory && !existingSimplified.contains(entry.path) && !visiblePending.contains(entry.path))
                missing.add(entry.path);
        }
        return missing;
    }

    private void drawFiles() {
        version++;shell(Screen.FILES,"",displayPath(directory));
        indexFolder();
        if(visibleFiles.isEmpty()){
            ui.body.addView(ui.note("这里没有可选择的影片或子文件夹。"));ui.space(ui.body,12);
            action("刷新文件夹",()->browse(directory)).requestFocus();return;
        }
        renderSelection();
        int pos=positions.getOrDefault(directory,0);
        if(pos>0 && pos<visibleFiles.size() && fileList.getAdapter().isEnabled(pos))fileList.setSelection(pos);
    }

    private void renderSelection() {
        java.util.Map<String,NasStore.Entry> seasons=new java.util.LinkedHashMap<>();
        for(NasStore.Entry entry:visibleFiles){
            if(!entry.isVideo())continue;
            String term=initialQuery(entry);
            if(PackFlow.supports(term,entry.path))
                seasons.putIfAbsent(SeasonPack.showOf(term)+" · 第 "+SeasonPack.seasonOf(term)+" 季",entry);
        }
        fileList=SeasonScreen.show(ui,displayPath(directory),visibleFiles,existingSimplified,otherTracks,
                visiblePending,checkedFiles,batchMode,pos->{
                    positions.put(directory,pos);
                    NasStore.Entry entry=visibleFiles.get(pos);
                    if(entry.directory){browse(entry.path);return;}
                    folders.remember(source,nasAccount,shareName,directory);
                    if(batchMode){
                        if(!checkedFiles.remove(entry.path))checkedFiles.add(entry.path);
                        renderSelection();
                    }else{
                        film=entry;query=initialQuery(entry);search();
                    }
                },this::toggleBatch,()->{
                    Set<String> missing=missingFiles();
                    if(checkedFiles.containsAll(missing))checkedFiles.clear();else checkedFiles.addAll(missing);
                    renderSelection();
                },this::submitBatch,seasons.isEmpty()?null:()->chooseFolderSeason(seasons));
    }

    private void chooseFolderSeason(java.util.Map<String,NasStore.Entry> seasons){
        List<NasStore.Entry> videos=new ArrayList<>(seasons.values());
        if(videos.size()==1){
            NasStore.Entry first=videos.get(0);
            startPack(shareName,first.path,initialQuery(first),false);
        }else new AlertDialog.Builder(this).setTitle("选择剧集和季")
                .setItems(seasons.keySet().toArray(new String[0]),(d,i)->{
                    NasStore.Entry first=videos.get(i);
                    startPack(shareName,first.path,initialQuery(first),false);
                }).setNegativeButton("返回",null).show();
    }

    private void startPack(String share,String anchor,String term,boolean openOnline){
        if(nas==null || !PackFlow.supports(term,anchor))return;
        boolean fromQueue=screen==Screen.QUEUE;
        ++version;
        shell(Screen.PACK,"整季字幕包",share+"  /  "+parent(anchor));
        pack=new PackFlow(this,ui,nas,queue,nasWorker,http,share,anchor,term,()->{
            if(fromQueue)showQueue();else browse(directory);
        });
        pack.start(openOnline);
    }

    private void toggleBatch() {
        batchMode=!batchMode;checkedFiles.clear();renderSelection();
    }

    private void submitBatch() {
        List<NasStore.Entry> videos=new ArrayList<>();
        List<String> queries=new ArrayList<>();
        for(NasStore.Entry entry:visibleFiles){
            if(!checkedFiles.contains(entry.path))continue;
            videos.add(entry);queries.add(initialQuery(entry));
        }
        if(videos.isEmpty())return;
        int added=queue.enqueue(shareName,videos,queries);
        checkedFiles.clear();batchMode=false;
        if(added==0)toast("所选影片已在队列中。");
        showQueue();
    }

    private Screen queueOrigin;

    private void showQueue() {
        if(screen!=Screen.PACK)queueOrigin=screen;
        version++;
        boolean inFolder=nas!=null && !shareName.isEmpty() && !visibleFiles.isEmpty()
                && queue.onlyFolder(shareName,directory);
        shell(Screen.QUEUE,"",inFolder?displayPath(directory):"");
        queuePane=new QueueScreen(this,ui,queue,nas==null?null:row->startPack(row.share,row.path,row.query,true));
        LinearLayout panel=inFolder?SeasonScreen.workspace(ui,displayPath(directory),visibleFiles):ui.body;
        ui.fill(panel,queuePane.view(),-1);
        queuePane.refresh();
    }

    private void returnFromQueue() {
        if(nas==null || queueOrigin==Screen.CONNECT){connectionForm();return;}
        if(shareName.isEmpty() || queueOrigin==Screen.OPENING)loadShares();else browse(directory);
    }

    private void queueChanged() {
        if(isFinishing() || isDestroyed())return;
        ui.setQueueCount(queue.size());
        if(screen==Screen.QUEUE && queuePane!=null){queuePane.refresh();return;}
        if(screen!=Screen.FILES || fileList==null)return;
        Set<String> current=queue.unfinishedPaths(shareName);
        if(visiblePending.equals(current))return;
        Set<String> completed=new HashSet<>(visiblePending);
        completed.removeAll(current);
        visiblePending=current;
        boolean changedFolder=false;
        for(String path:completed){
            if(parent(path).equals(directory)){changedFolder=true;break;}
        }
        if(changedFolder){
            int ticket=version;String path=directory;NasStore store=nas;
            job(nasWorker,ticket,()->store.list(path),entries->{
                listing=entries;indexFolder();renderSelection();
            },err->renderSelection());
        }else{
            checkedFiles.removeAll(current);renderSelection();
        }
    }
    private String initialQuery(NasStore.Entry entry){
        return queryForPath(entry.path);
    }
    static String queryForPath(String path){
        String initial=SubtitleService.suggestQuery(base(path));
        if(!initial.matches("(?i)S\\d{1,2}E\\d{1,3}"))return initial;
        String folder=parent(path);
        while(!folder.isEmpty()){
            String name=base(folder);folder=parent(folder);
            if(!name.matches("(?i)(season|series|s)[ ._-]*\\d{1,2}|第\\s*[\\d一二三四五六七八九十]+\\s*季"))
                return name.replace('.',' ').replace('_',' ')+" "+initial;
        }
        return initial;
    }
    private void search(){
        cancelSearch();
        int ticket=++version;selectedResult=0;matches=Collections.emptyList(); subtitleHead();
        TextView status=busy("正在 The Subtitle DB 搜索"+LANG_NAMES[language]+"字幕…");
        String term=query,filename=film.name,code=LANG_CODES[language];
        SubtitleService.FallbackListener update=unavailable->runOnUiThread(()->{
            if(active(ticket))status.setText((unavailable?"首选源暂时不可用。":"首选源未找到简中字幕。")+"正在查询 Subtitle Cat…");
        });
        pendingSearch=job(http,ticket,()->service.search(term,filename,code,update),data->{matches=data;showMatches();},
                e->{
                    problem(e instanceof SubtitleService.WrongEpisode?"未找到正确集数的简中字幕":"搜索失败",e,this::search);
                    offerSeasonPack();
                });
    }
    private void cancelSearch(){
        if(pendingSearch!=null){pendingSearch.cancel(true);pendingSearch=null;}
    }
    private void subtitleHead(){
        shell(Screen.SUBTITLES,"选择字幕",film.name);
        LinearLayout line=ui.row();
        TextView term=ui.button("搜索词   "+query,false,this::editQuery);term.setEllipsize(android.text.TextUtils.TruncateAt.END);
        term.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);ui.fill(line,term,38);ui.space(line,8);
        line.addView(ui.button(LANG_NAMES[language]+"   ▾",false,this::chooseLanguage),ui.size(-2,38));
        ui.body.addView(line);ui.space(ui.body,11);
    }
    private void chooseLanguage(){
        new AlertDialog.Builder(this).setTitle("字幕语言").setSingleChoiceItems(LANG_NAMES,language,(d,i)->{
            d.dismiss();language=i;prefs.edit().putInt("language",i).apply();search();
        }).setNegativeButton("取消",null).show();
    }
    private void editQuery(){
        EditText e=ui.edit("英文片名、剧名或 IMDb 编号",query,InputType.TYPE_CLASS_TEXT);
        LinearLayout wrapper=ui.col();ui.pad(wrapper,20,10);wrapper.addView(e,ui.size(-1,44));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("修改搜索词").setView(wrapper)
                .setNegativeButton("取消",null).setPositiveButton("搜索",null).create();
        dialog.setOnShowListener(ignored->{
            View submit=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            submit.setOnClickListener(v->{
                String typed=e.getText().toString().trim();
                if(typed.isEmpty()){e.setError("请输入片名或剧名");return;}
                dialog.dismiss();query=typed;search();
            });
            e.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            e.setOnEditorActionListener((v,id,event)->{if(id==EditorInfo.IME_ACTION_SEARCH){submit.performClick();return true;}return false;});
            e.requestFocus();
        });dialog.show();
    }
    private void showMatches(){
        subtitleHead();
        if(matches.isEmpty()){
            String where=LANG_CODES[language].equals("zh-cn")?"The Subtitle DB 和 Subtitle Cat 都未找到":"The Subtitle DB 未找到";
            ui.body.addView(ui.note(where+"匹配的"+LANG_NAMES[language]+"字幕。可以修改片名，或换一种语言。"));
            ui.space(ui.body,12);action("修改搜索词",this::editQuery).requestFocus();offerSeasonPack();return;
        }
        ui.body.addView(ui.note(matches.get(0).source.label+"   ·   "+matches.size()+" 个版本   ·   选择后保存到影片目录"));ui.space(ui.body,6);
        List<TvUi.Item> rows=new ArrayList<>();
        for(SubtitleService.Result r:matches)rows.add(new TvUi.Item(r.title,r.detail,"caption"));
        ListView list=ui.items(rows,pos->{selectedResult=pos;download(matches.get(pos));});ui.fill(ui.body,list,-1);
        list.requestFocus();list.setSelection(Math.min(selectedResult,matches.size()-1));
    }
    private void offerSeasonPack(){
        if(language==0 && film!=null && PackFlow.supports(query,film.path)){
            ui.space(ui.body,8);
            action("去 SubHD 选整季字幕包",()->startPack(shareName,film.path,query,true)).requestFocus();
        }
    }
    private void download(SubtitleService.Result result){
        if(saving)return;
        saving=true;int ticket=++version;NasStore store=nas;String path=film.path,tag=FILE_TAGS[language];
        shell(Screen.SUBTITLES,"保存字幕",film.name);busy("正在从 "+result.source.label+" 下载并写入 NAS…");
        job(nasWorker,ticket,()->{
            SubtitleService.Downloaded subtitle=service.download(result);
            return store.saveSubtitle(path,tag,subtitle.extension,subtitle.bytes);
        },value->{
            saving=false;saved=value;
            shell(Screen.SAVED,"字幕已保存",displayPath(parent(saved)));
            TextView filename=ui.text(base(saved),20,TvUi.PAPER,true);ui.pad(filename,14,14);
            filename.setBackground(ui.shape(TvUi.SURFACE,TvUi.LINE,8));ui.body.addView(filename,ui.size(-1,-2));
            ui.space(ui.body,12);ui.body.addView(ui.note("打开影片，在播放器的字幕菜单中选择这份外挂字幕。"));
            ui.space(ui.body,18);action("返回影片目录",()->browse(directory)).requestFocus();
            ui.space(ui.body,7);action("选择其他版本",this::showMatches);
        },err->{
            saving=false;problem("保存失败",err,()->download(result));ui.space(ui.body,6);
            action("重新选择字幕",this::showMatches);
        });
    }
    private TextView busy(String label){
        LinearLayout line=ui.row();ProgressBar spin=new ProgressBar(this);line.addView(spin,ui.size(22,22));
        TextView status=ui.note(label);
        ui.space(line,10);line.addView(status);ui.body.addView(line,ui.size(-1,44));return status;
    }
    private void problem(String title,Exception e,Runnable retry){
        // Busy indicator is the last child. Keep the title/language controls for recovery.
        ui.body.removeViewAt(ui.body.getChildCount()-1);
        ui.body.addView(ui.text(title,18,TvUi.AMBER,true));ui.space(ui.body,6);
        ui.body.addView(ui.note(message(e)));ui.space(ui.body,12);action("重试",retry).requestFocus();
    }
    private TextView action(String label,Runnable run){TextView v=ui.button(label,true,run);ui.body.addView(v,ui.size(-2,40));return v;}
    private void toast(String label){Toast.makeText(this,label,Toast.LENGTH_LONG).show();}
    private static String message(Exception e){String s=e.getLocalizedMessage();return s==null||s.isEmpty()?"操作失败，请重试。":s;}
    private interface Done<T>{void accept(T value);}
    private <T> Future<?> job(ExecutorService pool,int ticket,Callable<T> work,Done<T> success,Done<Exception> failed){
        return pool.submit(()->{
            try{T value=work.call();runOnUiThread(()->{if(active(ticket))success.accept(value);});}
            catch(Exception e){runOnUiThread(()->{if(active(ticket))failed.accept(e);});}
        });
    }
    private boolean active(int ticket){return version==ticket&&!isFinishing()&&!isDestroyed();}
    private void closeLater(NasStore store){
        if(store==null)return;
        // A connection may finish opening after Activity destruction.
        if(nasWorker.isShutdown())new Thread(store::close,"NAS-close").start();else nasWorker.execute(store::close);
    }
    @Override public void onBackPressed(){
        if(writing()){toast("字幕正在保存，请稍候。");return;}
        switch(screen){
            case CONNECT:
                if(backToFolderFromSettings && nas!=null && !shareName.isEmpty()){
                    backToFolderFromSettings=false;connecting=false;browse(directory,false);
                }else super.onBackPressed();
                break;
            case PACK: if(pack!=null)pack.back();break;
            case QUEUE:
                if(queuePane==null || !queuePane.back())returnFromQueue();
                break;
            case SAVED: case SUBTITLES: browse(directory);break;
            case FILES:
                if(batchMode && fileList!=null)toggleBatch();
                else if(directory.isEmpty())loadShares();else browse(parent(directory),false);
                break;
            case OPENING:loadShares();break;
            case SHARES:connectionForm();break;
            default: super.onBackPressed();
        }
    }
    @Override protected void onStop(){
        if(discoveryPicker!=null){discoveryPicker.dismiss();discoveryPicker=null;}
        super.onStop();
    }
    @Override protected void onDestroy(){
        if(pack!=null)pack.close();
        version++;queue.removeListener(queueListener);
        if(queuePane!=null)queuePane.back();
        cancelSearch();closeLater(nas);nas=null;nasWorker.shutdown();http.shutdownNow();super.onDestroy();
    }
}
