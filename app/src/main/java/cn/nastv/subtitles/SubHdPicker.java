package cn.nastv.subtitles;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native TV chooser plus the site's user-driven download and verification page. */
final class SubHdPicker {
    interface Receiver {void received(byte[] bytes,String suggestedName,String packLabel,List<String> seriesAliases);}
    private final Activity activity;
    private final String show;
    private final int season;
    private final Receiver receiver;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final TvUi ui;
    private final Dialog dialog;
    private final LinearLayout layout;
    private TextView status;
    private FrameLayout host;
    private RemoteWebView detail,popup;
    private boolean closed,receiving;

    static SubHdPicker open(Activity activity,String showTitle,int season,Receiver receiver){
        SubHdPicker picker=new SubHdPicker(activity,showTitle,season,receiver);
        picker.search();
        return picker;
    }
    private SubHdPicker(Activity activity,String show,int season,Receiver receiver){
        this.activity=activity;this.show=show;this.season=season;this.receiver=receiver;ui=new TvUi(activity);
        layout=ui.col();layout.setBackgroundColor(TvUi.BG);ui.pad(layout,18,12);
        dialog=new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(layout);
        dialog.setOnDismissListener(d->dispose());
        dialog.setOnKeyListener((d,key,event)->{
            if(key!=KeyEvent.KEYCODE_BACK||event.getAction()!=KeyEvent.ACTION_UP)return key==KeyEvent.KEYCODE_BACK;
            RemoteWebView current=currentWeb();
            if(current!=null&&current.canGoBack()&&!receiving)current.goBack();
            else if(popup!=null&&!receiving)removePopup();
            else close();
            return true;
        });
        dialog.show();
        Window window=dialog.getWindow();
        if(window!=null){
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
            window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
        }
    }
    void close(){
        if(!closed){dialog.dismiss();dispose();}
    }
    private void dispose(){
        if(closed)return;closed=true;worker.shutdownNow();main.removeCallbacksAndMessages(null);
        while(popup!=null)removePopup();
        destroy(detail);detail=null;
    }
    private static void destroy(WebView web){
        if(web==null)return;
        if(web.getParent() instanceof ViewGroup)((ViewGroup)web.getParent()).removeView(web);
        web.stopLoading();web.setWebChromeClient(null);web.setWebViewClient(new WebViewClient());web.destroy();
    }
    private void title(String heading){
        LinearLayout top=ui.row();
        TextView h=ui.text(heading,21,TvUi.PAPER,true);ui.fill(top,h,-2);
        TextView exit=ui.button("返回",false,this::close);top.addView(exit,ui.size(-2,34));
        layout.addView(top,ui.size(-1,38));
    }
    private TextView message(String text){TextView msg=ui.text(text,14,TvUi.MUTED,false);layout.addView(msg);return msg;}
    private void search(){
        if(closed)return;
        layout.removeAllViews();title("SubHD 整季字幕包");
        message(show+" · 第 "+season+" 季");ui.space(layout,10);message("正在核对简中字幕包的文件清单…");
        worker.execute(()->{
            try{
                List<SubHD.Pack> packs=SubHD.search(show,season);
                main.post(()->{if(!closed)list(packs);});
            }catch(Exception e){main.post(()->{if(!closed)error(e);});}
        });
    }
    private void list(List<SubHD.Pack> packs){
        layout.removeAllViews();title("选择 SubHD 整季字幕包");message(show+" · 第 "+season+" 季");ui.space(layout,8);
        if(packs.isEmpty()){
            message("SubHD 暂未列出可确认集号的同季简中字幕包。");return;
        }
        ScrollView scroller=new ScrollView(activity);LinearLayout cards=ui.col();scroller.addView(cards);
        ui.fill(layout,scroller,-1);
        TextView first=null;
        for(SubHD.Pack pack:packs){
            TextView select=ui.button(pack.label,false,()->openPage(pack));
            select.setSingleLine(false);select.setMaxLines(2);cards.addView(select,ui.size(-1,-2));
            ui.pad(select,12,6);ui.space(cards,3);cards.addView(ui.text(pack.detail,13,TvUi.MUTED,false));ui.space(cards,10);
            if(first==null)first=select;
        }
        if(first!=null)first.requestFocus();
    }
    private void error(Exception e){
        layout.removeAllViews();title("SubHD 查询失败");message(reason(e));ui.space(layout,8);
        TextView retry=ui.button("重新查询",true,this::search);layout.addView(retry,ui.size(-2,36));retry.requestFocus();
    }
    private static String reason(Exception e){return e.getMessage()==null?"请检查网络后重试。":e.getMessage();}
    private RemoteWebView currentWeb(){return popup!=null?popup:detail;}
    private void openPage(SubHD.Pack pack){
        layout.removeAllViews();title("SubHD · "+pack.label);
        status=message("方向键移光标，OK 点击官网的下载或验证按钮；移到上下边缘可滚动。");
        ui.space(layout,4);host=new FrameLayout(activity);ui.fill(layout,host,-1);
        try{
            detail=browser(pack);
            host.addView(detail,new FrameLayout.LayoutParams(-1,-1));detail.requestFocus();detail.loadUrl(pack.url);
        }catch(RuntimeException e){error(new IOException("此设备无法打开系统 WebView。请更新 Android System WebView 后重试。",e));}
    }
    private RemoteWebView browser(SubHD.Pack pack){
        RemoteWebView web=new RemoteWebView();
        WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(true);
        settings.setUseWideViewPort(true);settings.setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onCreateWindow(WebView view,boolean dialogWindow,boolean userGesture,Message msg){
                if(!userGesture||view!=currentWeb()||host.getChildCount()>=4||!SubHD.official(view.getUrl())||closed)return false;
                popup=browser(pack);popup.opener=(RemoteWebView)view;
                view.setVisibility(View.GONE);host.addView(popup,new FrameLayout.LayoutParams(-1,-1));
                popup.requestFocus();
                WebView.WebViewTransport transport=(WebView.WebViewTransport)msg.obj;
                transport.setWebView(popup);msg.sendToTarget();return true;
            }
            @Override public void onCloseWindow(WebView window){if(popup==window)removePopup();}
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                if(!request.isForMainFrame())return false;
                String target=request.getUrl().toString();
                if("about:blank".equals(target))return false;
                if(target.startsWith("blob:")&&SubHD.official(web.getUrl())){
                    download(pack,web,target,web.getSettings().getUserAgentString(),null,null,-1);return true;
                }
                if(!SubHD.official(target)){status.setText("此链接已离开 SubHD 下载站点。按返回可重新选择。");return true;}
                String path=request.getUrl().getPath();
                if("GET".equals(request.getMethod())&&SubHD.official(view.getUrl())&&path!=null&&
                        path.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(zip|rar|7z)$")){
                    download(pack,web,target,web.getSettings().getUserAgentString(),null,null,-1);return true;
                }
                return false;
            }
            @Override public void onPageFinished(WebView view,String url){
                if(closed||!SubHD.official(url)||view!=currentWeb())return;
                // Position the user's remote cursor at the visible official button. Only OK activates it.
                String path=Uri.parse(url).getPath();
                if(path!=null&&(path.startsWith("/a/")||path.startsWith("/down/"))){
                    final int moves=web.moves;
                    boolean verify=path.startsWith("/down/");
                    String button=verify?DOWNLOAD_PAGE_CONTROL:"document.querySelector('.subtitle-prepare-download')";
                    centerControl(web,url,moves,button);
                    if(verify)main.postDelayed(()->centerControl(web,url,moves,button),900);
                }
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest req,WebResourceError err){
                if(!closed&&req.isForMainFrame())status.setText("SubHD 页面加载失败。请返回后重试。");
            }
        });
        web.setDownloadListener((url,userAgent,disposition,mime,size)->download(pack,web,url,userAgent,disposition,mime,size));
        return web;
    }
    // The site's verification widgets may appear after page load. Only move the viewport and cursor.
    private static final String DOWNLOAD_PAGE_CONTROL="(function(){var a=Array.from(document.querySelectorAll('button,a,input[type=button],iframe,.cf-turnstile,[data-sitekey]')).filter(function(n){var r=n.getBoundingClientRect();return r.width>1&&r.height>1});return a.find(function(n){return /turnstile|captcha|challenge/i.test(n.className+' '+(n.getAttribute('src')||''))})||a.find(function(n){return /下载字幕|获取下载|点击下载|立即下载|下载文件|人机验证|验证后下载|开始验证/.test((n.innerText||n.value||'').trim())});})()";
    private void centerControl(RemoteWebView web,String page,int moves,String element){
        if(closed||currentWeb()!=web||moves!=web.moves||!page.equals(web.getUrl()))return;
        web.evaluateJavascript("(function(){var b="+element+";if(!b)return;document.documentElement.style.setProperty('scroll-behavior','auto','important');b.scrollIntoView({block:'center',behavior:'auto'});})()",v->{
            main.postDelayed(()->aimAtControl(web,page,moves,element),180);
            main.postDelayed(()->aimAtControl(web,page,moves,element),550);
        });
    }
    private void aimAtControl(RemoteWebView web,String page,int moves,String element){
        if(closed||currentWeb()!=web||moves!=web.moves||!page.equals(web.getUrl()))return;
        web.evaluateJavascript("(function(){var b="+element+";if(!b)return null;var r=b.getBoundingClientRect(),v=window.visualViewport;return [(r.left+r.width/2-(v?v.offsetLeft:0))/(v?v.width:innerWidth),(r.top+r.height/2-(v?v.offsetTop:0))/(v?v.height:innerHeight)];})()",value->{
            if(closed||currentWeb()!=web||moves!=web.moves)return;
            try{JSONArray pos=new JSONArray(value);web.point((float)pos.getDouble(0),(float)pos.getDouble(1));}catch(Exception ignored){}
        });
    }
    private void removePopup(){
        if(popup==null)return;
        RemoteWebView parent=popup.opener;destroy(popup);
        popup=parent==detail?null:parent;
        if(parent!=null&&!closed){parent.setVisibility(View.VISIBLE);parent.requestFocus();}
    }
    private void download(SubHD.Pack pack,RemoteWebView web,String url,String agent,String disposition,String mime,long size){
        if(closed||receiving||currentWeb()!=web)return;
        String referring=web.getUrl();
        if((referring==null||"about:blank".equals(referring))&&web.opener!=null)referring=web.opener.getUrl();
        if(!SubHD.official(referring)||url==null){status.setText("下载请求没有来自 SubHD 官网。");return;}
        if(size>SubHD.MAX_PACK_BYTES){status.setText("字幕包大于 12 MB，请选择较小的版本。");return;}
        if(url.startsWith("blob:")){
            if(!SubHD.official(url.substring(5))||!Uri.parse(url.substring(5)).getHost().equalsIgnoreCase(Uri.parse(referring).getHost())){
                status.setText("无法确认字幕包来自当前 SubHD 页面。");return;
            }
            readBlob(pack,web,url,disposition,mime);return;
        }
        if(!SubHD.official(url)){status.setText("SubHD 返回了未识别的下载域名。");return;}
        receiving=true;status.setText("正在接收字幕包…");
        final String originPage=referring;
        worker.execute(()->{
            try{
                SubHD.Reply r=SubHD.fetch(url,originPage,agent==null?SubHD.USER_AGENT:agent,true,CookieManager.getInstance()::getCookie);
                String file=URLUtil.guessFileName(r.url,r.disposition==null?disposition:r.disposition,mime);
                main.post(()->deliver(pack,r.bytes,file));
            }catch(Exception e){main.post(()->failure(reason(e)));}
        });
    }
    private void readBlob(SubHD.Pack pack,RemoteWebView web,String url,String disposition,String mime){
        String key="__nasSubtitles"+UUID.randomUUID().toString().replace("-","");
        String init="(async function(){try{var r=await fetch("+JSONObject.quote(url)+");var b=await r.blob();if(!b.size||b.size>"+SubHD.MAX_PACK_BYTES+")throw Error();window."+key+"=b;}catch(e){window."+key+"=false;}})()";
        receiving=true;status.setText("正在接收字幕包…");
        web.evaluateJavascript(init,null);
        BlobTransfer transfer=new BlobTransfer(web,key,pack,URLUtil.guessFileName(url,disposition,mime));
        transfer.poll();
    }
    private final class BlobTransfer {
        final RemoteWebView web;final String key,name;final SubHD.Pack pack;
        final ByteArrayOutputStream out=new ByteArrayOutputStream();
        final long until=SystemClock.elapsedRealtime()+90000;
        int size;
        BlobTransfer(RemoteWebView web,String key,SubHD.Pack pack,String name){this.web=web;this.key=key;this.pack=pack;this.name=name;}
        void poll(){
            if(closed||currentWeb()!=web)return;
            if(SystemClock.elapsedRealtime()>until){fail();return;}
            String ref="window."+key;
            web.evaluateJavascript("(function(){var b="+ref+";if(b===false)return -1;if(!b)return 0;return b.size;})()",value->{
                if(closed)return;
                try{size=Integer.parseInt(value);}catch(Exception e){fail();return;}
                if(size==0){main.postDelayed(this::poll,120);return;}
                if(size<0||size>SubHD.MAX_PACK_BYTES){fail();return;}
                slice();
            });
        }
        void slice(){
            if(closed||currentWeb()!=web)return;
            int start=out.size(),end=Math.min(size,start+49152);
            String run="(function(){window."+key+"chunk=null;var r=new FileReader();r.onload=function(){window."+key+"chunk=String(r.result).split(',')[1];};r.onerror=function(){window."+key+"chunk=false};r.readAsDataURL(window."+key+".slice("+start+","+end+"));})()";
            web.evaluateJavascript(run,v->read(end));
        }
        void read(int end){
            if(closed||currentWeb()!=web)return;
            if(SystemClock.elapsedRealtime()>until){fail();return;}
            web.evaluateJavascript("window."+key+"chunk",value->{
                if(closed)return;
                if("null".equals(value)){main.postDelayed(()->read(end),30);return;}
                try{
                    Object obj=new JSONTokener(value).nextValue();
                    if(!(obj instanceof String)||((String)obj).length()>66000)throw new IOException();
                    byte[] block=Base64.decode((String)obj,Base64.DEFAULT);
                    if(out.size()+block.length!=end)throw new IOException();
                    out.write(block,0,block.length);
                    if(end==size){clear();deliver(pack,out.toByteArray(),name);}else slice();
                }catch(Exception e){fail();}
            });
        }
        void clear(){if(!closed&&web==currentWeb())web.evaluateJavascript("delete window."+key+";delete window."+key+"chunk",null);}
        void fail(){clear();failure("字幕包读取失败或已超过大小限制。请重新点选官方下载。");}
    }
    private void failure(String msg){if(!closed){receiving=false;status.setText(msg);}}
    private void deliver(SubHD.Pack pack,byte[] bytes,String name){
        if(closed)return;
        if(bytes.length==0){failure("收到的字幕包是空的。");return;}
        close();receiver.received(bytes,name,pack.label,pack.aliases);
    }
    private final class RemoteWebView extends WebView {
        RemoteWebView opener;
        int moves;
        final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        float pointerX=-1,pointerY=-1;
        RemoteWebView(){super(activity);setFocusable(true);setFocusableInTouchMode(true);}
        void point(float fx,float fy){
            pointerX=clamp(getWidth()*fx,getWidth());pointerY=clamp(getHeight()*fy,getHeight());invalidate();
        }
        float clamp(float value,int limit){return Math.max(3,Math.min(limit-3,value));}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);if(pointerX<0)point(.5f,.5f);
            float x=pointerX+getScrollX(),y=pointerY+getScrollY();
            ink.setStyle(Paint.Style.STROKE);ink.setStrokeWidth(ui.dp(4));ink.setColor(0xe522241c);canvas.drawCircle(x,y,ui.dp(7),ink);
            ink.setStrokeWidth(ui.dp(2));ink.setColor(0xffffda89);canvas.drawCircle(x,y,ui.dp(7),ink);
            ink.setStyle(Paint.Style.FILL);canvas.drawCircle(x,y,ui.dp(2),ink);
        }
        @Override public boolean dispatchKeyEvent(KeyEvent e){
            int key=e.getKeyCode();
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){
                if(e.getAction()==KeyEvent.ACTION_UP){
                    moves++;
                    long time=SystemClock.uptimeMillis();
                    MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,pointerX,pointerY,0);
                    MotionEvent up=MotionEvent.obtain(time,time+30,MotionEvent.ACTION_UP,pointerX,pointerY,0);
                    try{dispatchTouchEvent(down);dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}
                }
                return true;
            }
            int dx=key==KeyEvent.KEYCODE_DPAD_RIGHT?1:key==KeyEvent.KEYCODE_DPAD_LEFT?-1:0;
            int dy=key==KeyEvent.KEYCODE_DPAD_DOWN?1:key==KeyEvent.KEYCODE_DPAD_UP?-1:0;
            if(dx==0&&dy==0)return super.dispatchKeyEvent(e);
            if(e.getAction()==KeyEvent.ACTION_DOWN){
                moves++;
                int step=ui.dp(e.getRepeatCount()>3?32:12);
                float x=pointerX+dx*step,y=pointerY+dy*step;
                if(dy!=0&&(y<0||y>getHeight()))scrollBy(0,dy*ui.dp(46));
                if(dx!=0&&(x<0||x>getWidth()))scrollBy(dx*ui.dp(46),0);
                pointerX=clamp(x,getWidth());pointerY=clamp(y,getHeight());invalidate();
            }return true;
        }
    }
}
