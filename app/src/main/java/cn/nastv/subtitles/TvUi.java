package cn.nastv.subtitles;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.List;

final class TvUi {
    static final int BG=0xff151b18, RAIL=0xff111b16, SURFACE=0xff222c25, LINE=0xff364139;
    static final int PAPER=0xfff7f4e8, MUTED=0xffb1bcb1, AMBER=0xffedc78b, INK=0xff25251b;
    static final int FOCUS=0xffffd393, GOOD=0xffb7d5b5;
    private static final int[] FOCUSED={android.R.attr.state_focused}, PRESSED={android.R.attr.state_pressed};
    final Activity a;
    LinearLayout body;
    private TextView queueBadge;
    private Runnable openQueue;
    private int queueCount;
    private boolean queueActive;

    TvUi(Activity activity) { a=activity; }
    int dp(int n) { return Math.round(a.getResources().getDisplayMetrics().density*n); }
    LinearLayout col() { LinearLayout v=new LinearLayout(a); v.setOrientation(LinearLayout.VERTICAL); return v; }
    LinearLayout row() { LinearLayout v=new LinearLayout(a); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    LinearLayout.LayoutParams size(int w,int h) {
        return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));
    }
    void pad(View v,int x,int y) { v.setPadding(dp(x),dp(y),dp(x),dp(y)); }
    void space(LinearLayout box,int n) {
        boolean vertical=box.getOrientation()==LinearLayout.VERTICAL;
        box.addView(new View(a),size(vertical?1:n,vertical?n:1));
    }
    void fill(LinearLayout box,View view,int cross) {
        LinearLayout.LayoutParams lp=box.getOrientation()==LinearLayout.VERTICAL?size(cross,0):size(0,cross);
        lp.weight=1; box.addView(view,lp);
    }
    TextView text(String s,int sp,int color,boolean bold) {
        TextView v=new TextView(a); v.setText(s); v.setTextSize(sp); v.setTextColor(color);
        v.setIncludeFontPadding(false);
        if(bold) v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        return v;
    }
    void clip(TextView t,int lines) { t.setMaxLines(lines); t.setEllipsize(TextUtils.TruncateAt.END); }
    GradientDrawable shape(int fill,int border,int r) {
        GradientDrawable d=new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(r));
        if(border!=0) d.setStroke(dp(1),border);
        return d;
    }
    StateListDrawable background(int idle,int border,int focused,int focusedBorder,int r) {
        StateListDrawable d=new StateListDrawable();
        GradientDrawable ring=shape(focused,0,r); ring.setStroke(dp(2),focusedBorder);
        d.addState(FOCUSED,ring); d.addState(PRESSED,shape(focused,focusedBorder,r));
        d.addState(new int[0],shape(idle,border,r)); return d;
    }
    TextView button(String label,boolean primary,Runnable click) {
        TextView b=text(label,15,PAPER,true);
        b.setFocusable(true); b.setClickable(true); b.setGravity(Gravity.CENTER);
        int idle=primary?AMBER:SURFACE;
        b.setBackground(background(idle,primary?0:LINE,primary?0xfff8dfb0:0xff383a2d,FOCUS,6));
        b.setTextColor(new ColorStateList(new int[][]{FOCUSED,PRESSED,new int[0]},
                new int[]{primary?INK:PAPER,primary?INK:PAPER,primary?INK:PAPER}));
        b.setSingleLine(); pad(b,14,0); b.setOnClickListener(v->click.run());
        return b;
    }
    EditText edit(String hint,String value,int type) {
        EditText e=new EditText(a); e.setSingleLine(true); e.setInputType(type);
        e.setTextSize(16); e.setHintTextColor(0xff8da195); e.setTextColor(PAPER);
        e.setBackground(background(0xff1b2722,LINE,0xff25362c,AMBER,7)); pad(e,12,0);
        e.setHint(hint); e.setText(value); e.setSelectAllOnFocus(true); return e;
    }
    void field(LinearLayout line,String label,EditText e) {
        LinearLayout box=col(); box.addView(text(label,13,MUTED,false)); space(box,6);
        box.addView(e,size(-1,40)); fill(line,box,-2);
    }
    void navigation(int count,boolean activeQueue,Runnable action) {
        queueActive=activeQueue; openQueue=action; setQueueCount(count);
    }
    void setQueueCount(int count) {
        queueCount=count;
        if(queueBadge!=null)queueBadge.setText(String.valueOf(count));
    }
    void shell(String title,String subtitle,boolean connection,String server,Runnable library,Runnable connect) {
        int gutter=a.getResources().getConfiguration().screenWidthDp<850?22:32;
        LinearLayout outer=col(); outer.setBackgroundColor(BG);
        LinearLayout head=row(); pad(head,gutter,0); head.setBackgroundColor(RAIL);
        head.addView(new Glyph(a,"brand",AMBER),size(33,31)); space(head,8);
        TextView brand=text("幕匣",22,PAPER,true); brand.setLetterSpacing(.07f);
        head.addView(brand); space(head,30);
        tab(head,"影片目录",!connection&&!queueActive,false,server!=null,library);
        space(head,12); tab(head,"下载队列",queueActive,true,openQueue!=null,openQueue);
        fill(head,new View(a),1);
        LinearLayout nas=row(); pad(nas,10,0); nas.setGravity(Gravity.CENTER);
        nas.setFocusable(true); nas.setClickable(true);
        nas.setBackground(background(connection?SURFACE:0,connection?LINE:0,0xff343c30,FOCUS,6));
        View dot=new View(a); dot.setBackground(shape(server==null?MUTED:GOOD,0,8));
        nas.addView(dot,size(7,7)); space(nas,8);
        nas.addView(text(server==null?"连接 NAS":"NAS 已连接",13,server==null?MUTED:PAPER,false));
        nas.setContentDescription(server==null?"连接 NAS":"已连接 "+server+"，打开连接设置");
        nas.setOnClickListener(v->connect.run()); head.addView(nas,size(-2,33));
        outer.addView(head,size(-1,53)); rule(outer);

        LinearLayout canvas=col(); canvas.setPadding(dp(gutter),dp(10),dp(gutter),dp(12));
        fill(outer,canvas,-1);
        if(!title.isEmpty()){
            TextView heading=text(title,27,PAPER,true); clip(heading,1); canvas.addView(heading);
            if(!subtitle.isEmpty()){
                space(canvas,3); TextView sub=text(subtitle,14,MUTED,false); clip(sub,2); canvas.addView(sub);
            }
            space(canvas,11);
        }else if(!subtitle.isEmpty()){
            LinearLayout crumb=row(); crumb.addView(new Glyph(a,"folder",MUTED),size(16,16)); space(crumb,8);
            TextView location=text(subtitle,13,MUTED,false); location.setSingleLine(true);
            location.setEllipsize(TextUtils.TruncateAt.START); fill(crumb,location,-2);
            canvas.addView(crumb,size(-1,23)); space(canvas,7);
        }
        body=col(); fill(canvas,body,-1);
        rule(outer); LinearLayout foot=row(); pad(foot,gutter,0);
        key(foot,"↔ ↕","移动"); key(foot,"OK","选择"); key(foot,"↶","返回");
        outer.addView(foot,size(-1,28)); a.setContentView(outer);
    }
    private void key(LinearLayout line,String key,String description){
        TextView cap=text(key,10,MUTED,true); pad(cap,4,0); cap.setGravity(Gravity.CENTER);
        cap.setBackground(shape(0,LINE,3)); line.addView(cap,size(-2,15)); space(line,5);
        line.addView(text(description,10,MUTED,false)); space(line,17);
    }
    private void tab(LinearLayout head,String name,boolean active,boolean count,boolean enabled,Runnable action) {
        LinearLayout tab=new LinearLayout(a){
            private final Paint indicator=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void dispatchDraw(Canvas canvas){
                super.dispatchDraw(canvas);
                if(active){
                    indicator.setColor(AMBER);
                    canvas.drawRect(dp(7),getHeight()-dp(3),getWidth()-dp(7),getHeight(),indicator);
                }
            }
        };
        tab.setFocusable(enabled); tab.setClickable(enabled); tab.setEnabled(enabled);
        pad(tab,10,0); tab.setGravity(Gravity.CENTER);
        tab.addView(text(name,15,enabled?(active?PAPER:MUTED):0xff879388,active));
        if(count){
            space(tab,6); queueBadge=text(String.valueOf(queueCount),11,PAPER,false);
            queueBadge.setTypeface(Typeface.MONOSPACE); queueBadge.setGravity(Gravity.CENTER);
            queueBadge.setMinWidth(dp(19)); pad(queueBadge,4,0); queueBadge.setBackground(shape(0,0xff526054,4));
            tab.addView(queueBadge,size(-2,18));
        }
        tab.setBackground(background(0,0,0xff303831,FOCUS,5));
        if(enabled)tab.setOnClickListener(v->action.run()); head.addView(tab,size(-2,43));
    }
    void rule(LinearLayout box) { View line=new View(a); line.setBackgroundColor(LINE); box.addView(line,new LinearLayout.LayoutParams(-1,dp(1))); }
    TextView note(String content) {
        TextView note=text(content,14,MUTED,false); note.setLineSpacing(dp(2),1f); return note;
    }
    static final class Item {
        final String title,detail,icon;
        Item(String title,String detail,String icon) { this.title=title; this.detail=detail; this.icon=icon; }
    }
    interface OnPick { void pick(int position); }
    ListView list() {
        ListView list=new ListView(a);
        list.setPadding(dp(1),dp(2),dp(1),dp(2)); list.setClipToPadding(false);
        GradientDrawable ring=shape(0x0dffd393,0,6); ring.setStroke(dp(2),FOCUS);
        StateListDrawable selector=new StateListDrawable();
        selector.addState(FOCUSED,ring);
        selector.addState(PRESSED,ring);
        selector.addState(new int[0],new ColorDrawable(0));
        list.setSelector(selector); list.setDrawSelectorOnTop(true);
        list.setDivider(new ColorDrawable(LINE)); list.setDividerHeight(dp(1)); list.setCacheColorHint(0);
        list.setVerticalScrollBarEnabled(true); list.setScrollbarFadingEnabled(true);
        return list;
    }
    ListView items(List<Item> rows,OnPick pick) {
        ListView list=list();
        list.setAdapter(new BaseAdapter(){
            public int getCount(){return rows.size();}
            public Object getItem(int i){return rows.get(i);}
            public long getItemId(int i){return i;}
            public View getView(int i,View reuse,ViewGroup parent){
                Item item=rows.get(i);
                ItemView line=reuse==null?new ItemView(): (ItemView)reuse;
                line.icon.type=item.icon; line.icon.invalidate(); line.title.setText(item.title);
                line.detail.setText(item.detail); return line;
            }
        });
        list.setOnItemClickListener((parent,v,pos,id)->pick.pick(pos));
        return list;
    }
    private final class ItemView extends LinearLayout {
        final Glyph icon; final TextView title,detail;
        ItemView(){
            super(a); setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            pad(this,10,0); setLayoutParams(new android.widget.AbsListView.LayoutParams(-1,dp(55)));
            icon=new Glyph(a,"film",AMBER); icon.setBackground(shape(SURFACE,0,6)); addView(icon,size(32,32));
            space(this,11); LinearLayout labels=col(); title=text("",16,PAPER,true);
            title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.MARQUEE); title.setMarqueeRepeatLimit(-1);
            detail=text("",12,MUTED,false); clip(detail,1); labels.addView(title); space(labels,3); labels.addView(detail);
            fill(this,labels,-2); space(this,8); addView(new Glyph(a,"arrow",MUTED),size(18,18));
        }
    }
    static final class Glyph extends View {
        String type; final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Glyph(Context ctx,String type,int color){super(ctx);this.type=type;p.setColor(color);p.setStrokeWidth(1.65f);
            p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);c.save();
            float scale=Math.min(getWidth(),getHeight())/24f;
            c.translate((getWidth()-24*scale)/2,(getHeight()-24*scale)/2);c.scale(scale,scale);
            switch(type){
                case "folder":
                    android.graphics.Path v=new android.graphics.Path(); v.moveTo(3.6f,7);v.lineTo(3.6f,5.3f);
                    v.lineTo(9,5.3f);v.lineTo(11,8);v.lineTo(20.4f,8);v.lineTo(20.4f,18.7f);
                    v.lineTo(3.6f,18.7f);v.close();c.drawPath(v,p);break;
                case "server":
                    c.drawRoundRect(3,4.5f,21,11.1f,1.5f,1.5f,p);c.drawRoundRect(3,13,21,19.6f,1.5f,1.5f,p);
                    c.drawPoint(17.8f,7.8f,p);c.drawPoint(17.8f,16.4f,p);break;
                case "arrow": c.drawLine(10,7,15,12,p);c.drawLine(15,12,10,17,p);break;
                case "brand":
                    c.drawRoundRect(1.2f,3.5f,22.8f,20.5f,3.8f,3.8f,p);
                    c.drawLine(5.5f,11.3f,9.8f,11.3f,p); c.drawLine(12.7f,11.3f,17.8f,11.3f,p);
                    c.drawLine(5.5f,15.2f,14.6f,15.2f,p); break;
                case "check":
                    c.drawLine(5.5f,12,10.2f,16.3f,p); c.drawLine(10.2f,16.3f,18.5f,7.8f,p); break;
                case "download":
                    c.drawLine(12,3.5f,12,15,p);c.drawLine(7.5f,10.8f,12,15,p);c.drawLine(12,15,16.5f,10.8f,p);
                    c.drawLine(4,15.8f,4,20,p);c.drawLine(4,20,20,20,p);c.drawLine(20,20,20,15.8f,p);break;
                case "clock":
                    c.drawCircle(12,12,8.5f,p);c.drawLine(12,7,12,12,p);c.drawLine(12,12,15.8f,14,p);break;
                case "blank":break;
                case "caption":
                    c.drawRoundRect(4,6,20,18,2,2,p);c.drawLine(7,12,10,12,p);c.drawLine(12.5f,12,16.5f,12,p);
                    c.drawLine(7,15,14,15,p);break;
                default: c.drawRoundRect(4,5,20,19,2,2,p);c.drawLine(4,9.5f,20,9.5f,p);
                    c.drawLine(8,5,6,9.5f,p);c.drawLine(14,5,12,9.5f,p);break;
            }c.restore();
        }
    }
}
