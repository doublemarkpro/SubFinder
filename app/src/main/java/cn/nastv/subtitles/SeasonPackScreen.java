package cn.nastv.subtitles;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SeasonPackScreen {
    interface Save { void save(Map<String, Integer> chosen); }

    static final class Row {
        final String videoPath, episode, blockedReason;
        final List<String> candidateNames;

        Row(String videoPath,String episode,List<String> candidateNames,String blockedReason) {
            this.videoPath=videoPath;
            this.episode=episode;
            this.candidateNames=new ArrayList<>(candidateNames);
            this.blockedReason=blockedReason==null?"":blockedReason;
        }

        boolean eligible() { return blockedReason.isEmpty() && !candidateNames.isEmpty(); }
    }

    private SeasonPackScreen() {}

    static void sources(TvUi ui,String seasonLabel,List<TvUi.Item> onlinePacks,TvUi.OnPick pickOnline,
                        Runnable chooseNas,Runnable retry,String status) {
        LinearLayout body=ui.body;
        body.removeAllViews();
        if(seasonLabel!=null && !seasonLabel.isEmpty()) {
            TextView season=ui.text(seasonLabel,18,TvUi.PAPER,true);
            ui.clip(season,2);
            body.addView(season);
            ui.space(body,6);
        }
        if(status!=null && !status.isEmpty()) {
            TextView note=ui.note(status);
            ui.clip(note,4);
            body.addView(note);
            ui.space(body,8);
        }
        ListView list=ui.items(onlinePacks,pickOnline);
        ui.fill(body,list,-1);
        ui.space(body,8);
        LinearLayout actions=ui.row();
        TextView retryButton=null,nasButton=null;
        if(retry!=null) {
            retryButton=ui.button("重新搜索 SubHD",onlinePacks.isEmpty(),retry);
            actions.addView(retryButton,ui.size(-2,38));
        }
        if(chooseNas!=null) {
            if(retryButton!=null)ui.space(actions,8);
            nasButton=ui.button("从 NAS 选择字幕包",false,chooseNas);
            actions.addView(nasButton,ui.size(-2,38));
        }
        if(actions.getChildCount()>0)body.addView(actions);
        if(!onlinePacks.isEmpty())list.requestFocus();
        else if(retryButton!=null)retryButton.requestFocus();
        else if(nasButton!=null)nasButton.requestFocus();
    }

    static void preview(TvUi ui,String packageName,List<Row> rows,Save save,Runnable changePack) {
        new Preview(ui,packageName,rows,save,changePack).show();
    }

    private static String basename(String path) {
        return path.substring(Math.max(path.lastIndexOf('/'),path.lastIndexOf('\\'))+1);
    }

    private static final class Preview {
        final TvUi ui;
        final String label;
        final List<Row> rows;
        final Save save;
        final Runnable changePack;
        final Map<String,Integer> resolved=new LinkedHashMap<>();
        final Set<String> selected=new HashSet<>();
        final BaseAdapter adapter=new BaseAdapter() {
            public int getCount(){return rows.size();}
            public Row getItem(int i){return rows.get(i);}
            public long getItemId(int i){return i;}
            public View getView(int i,View reuse,ViewGroup parent) {
                EpisodeView line=reuse==null?new EpisodeView():(EpisodeView)reuse;
                line.bind(getItem(i));
                return line;
            }
        };
        ListView list;
        TextView all,confirm,inspector;
        boolean submitted;

        Preview(TvUi ui,String label,List<Row> rows,Save save,Runnable changePack) {
            this.ui=ui; this.label=label; this.rows=rows; this.save=save; this.changePack=changePack;
            for(Row row:rows) {
                if(row.eligible() && row.candidateNames.size()==1) {
                    resolved.put(row.videoPath,0);
                    selected.add(row.videoPath);
                }
            }
        }

        void show() {
            LinearLayout body=ui.body;
            body.removeAllViews();
            LinearLayout header=ui.row();
            TextView pack=ui.text(label,17,TvUi.PAPER,true);
            ui.clip(pack,2);
            ui.fill(header,pack,-2);
            if(changePack!=null) {
                ui.space(header,8);
                header.addView(ui.button("换一个字幕包",false,changePack),ui.size(-2,35));
            }
            ui.space(header,8);
            all=ui.button("",false,()->{
                if(selected.size()==resolved.size())selected.clear();
                else selected.addAll(resolved.keySet());
                refresh();
            });
            header.addView(all,ui.size(-2,35));
            body.addView(header);
            ui.space(body,8);
            list=ui.list();
            list.setAdapter(adapter);
            list.setOnItemClickListener((parent,view,n,id)->open(n));
            list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> p,View v,int pos,long id){inspect(pos);}
                public void onNothingSelected(AdapterView<?> p){}
            });
            ui.fill(body,list,-1);
            ui.rule(body); ui.space(body,6);
            LinearLayout footer=ui.row();
            inspector=ui.note("");
            ui.clip(inspector,2);
            ui.fill(footer,inspector,-2);
            ui.space(footer,10);
            confirm=ui.button("",true,()->{
                if(submitted || selected.isEmpty())return;
                LinkedHashMap<String,Integer> result=new LinkedHashMap<>();
                for(Row row:rows)if(selected.contains(row.videoPath))result.put(row.videoPath,resolved.get(row.videoPath));
                submitted=true;
                available(confirm,false);
                save.save(Collections.unmodifiableMap(result));
            });
            footer.addView(confirm,ui.size(-2,40));
            body.addView(footer,ui.size(-1,44));
            // Long seasons remain navigable without having to scroll through every episode to reach Save.
            list.setOnKeyListener((v,key,event)-> {
                if(key!=KeyEvent.KEYCODE_DPAD_RIGHT || !confirm.isEnabled())return false;
                if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)confirm.requestFocus();
                return true;
            });
            confirm.setOnKeyListener((v,key,event)->{
                if(key!=KeyEvent.KEYCODE_DPAD_LEFT || rows.isEmpty())return false;
                if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)list.requestFocus();
                return true;
            });
            refresh();
            if(!rows.isEmpty()) {
                int start=0;
                for(int i=0;i<rows.size();i++)if(rows.get(i).eligible()){start=i;break;}
                list.requestFocus();
                list.setSelection(start);
                inspect(start);
            }
        }

        void refresh() {
            adapter.notifyDataSetChanged();
            all.setText(!resolved.isEmpty() && selected.size()==resolved.size()?"取消全选":"全选已匹配");
            available(all,!resolved.isEmpty());
            confirm.setText("保存 "+selected.size()+" 集");
            available(confirm,!submitted && !selected.isEmpty());
            inspect(list.getSelectedItemPosition());
        }

        String state(Row row) {
            if(!row.blockedReason.isEmpty())return row.blockedReason;
            if(row.candidateNames.isEmpty())return "包内未匹配";
            if(!resolved.containsKey(row.videoPath))return "选字幕版本";
            return selected.contains(row.videoPath)?"已勾选":"未勾选";
        }

        void inspect(int index) {
            if(inspector==null)return;
            if(index<0 || index>=rows.size()) {
                inspector.setText(rows.isEmpty()?"此目录没有可匹配的剧集。":"选择要保存字幕的剧集。");
                return;
            }
            Row row=rows.get(index);
            Integer version=resolved.get(row.videoPath);
            if(version!=null)inspector.setText("字幕文件  "+row.candidateNames.get(version));
            else if(row.eligible())inspector.setText("此集有 "+row.candidateNames.size()+" 个字幕版本，按确定选择。");
            else inspector.setText(state(row)+"  ·  "+basename(row.videoPath));
        }

        void open(int pos) {
            Row row=rows.get(pos);
            if(!row.eligible())return;
            if(row.candidateNames.size()==1) {
                if(!selected.add(row.videoPath))selected.remove(row.videoPath);
                refresh(); inspect(pos); return;
            }
            choose(row,pos);
        }

        void choose(Row row,int position) {
            Dialog dialog=new Dialog(ui.a);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout content=ui.col();
            content.setBackground(ui.shape(TvUi.SURFACE,TvUi.LINE,9));
            ui.pad(content,16,13);
            content.addView(ui.text(row.episode+"  选择字幕版本",21,TvUi.PAPER,true));
            TextView video=ui.note(basename(row.videoPath));
            ui.clip(video,2); ui.space(content,5); content.addView(video); ui.space(content,8);
            List<TvUi.Item> choices=new ArrayList<>();
            for(int i=0;i<row.candidateNames.size();i++) {
                String name=row.candidateNames.get(i);
                boolean active=selected.contains(row.videoPath) && Integer.valueOf(i).equals(resolved.get(row.videoPath));
                choices.add(new TvUi.Item(basename(name),name,active?"check":"caption"));
            }
            ListView options=ui.items(choices,i->{
                resolved.put(row.videoPath,i); selected.add(row.videoPath); dialog.dismiss();
            });
            ui.fill(content,options,-1); ui.space(content,7);
            TextView member=ui.text("",13,TvUi.AMBER,false);
            ui.clip(member,3); content.addView(member); ui.space(content,7);
            options.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> p,View v,int index,long id) {
                    member.setText(row.candidateNames.get(index));
                }
                public void onNothingSelected(AdapterView<?> p){}
            });
            LinearLayout buttons=ui.row();
            buttons.addView(ui.button("跳过本集",false,()->{
                selected.remove(row.videoPath); resolved.remove(row.videoPath); dialog.dismiss();
            }),ui.size(-2,36));
            ui.space(buttons,8);
            buttons.addView(ui.button("返回",false,dialog::dismiss),ui.size(-2,36));
            content.addView(buttons);
            dialog.setContentView(content);
            dialog.setOnDismissListener(d->{
                if(!list.isAttachedToWindow())return;
                refresh(); list.requestFocus(); list.setSelection(position); inspect(position);
            });
            dialog.show();
            Window window=dialog.getWindow();
            if(window!=null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.getDecorView().setSystemUiVisibility(ui.a.getWindow().getDecorView().getSystemUiVisibility());
                window.setLayout(Math.min(ui.dp(660),(int)(ui.a.getResources().getDisplayMetrics().widthPixels*.9)),
                        (int)(ui.a.getResources().getDisplayMetrics().heightPixels*.77));
            }
            options.requestFocus();
            Integer current=resolved.get(row.videoPath);
            if(current!=null)options.setSelection(current);
        }

        final class EpisodeView extends LinearLayout {
            final TvUi.Glyph icon;
            final TextView title,video,status;
            EpisodeView() {
                super(ui.a);
                setGravity(Gravity.CENTER_VERTICAL);
                ui.pad(this,10,0);
                setLayoutParams(new AbsListView.LayoutParams(-1,ui.dp(51)));
                icon=new TvUi.Glyph(ui.a,"blank",TvUi.AMBER);
                addView(icon,ui.size(20,20)); ui.space(this,9);
                LinearLayout copy=ui.col();
                title=ui.text("",15,TvUi.PAPER,true); ui.clip(title,1);
                video=ui.text("",12,TvUi.MUTED,false); ui.clip(video,1);
                copy.addView(title); ui.space(copy,2); copy.addView(video);
                ui.fill(this,copy,-2); ui.space(this,9);
                status=ui.text("",12,TvUi.MUTED,true); ui.clip(status,2); addView(status,ui.size(-2,-2));
                status.setMaxWidth(ui.dp(155));
            }
            void bind(Row row) {
                boolean checked=selected.contains(row.videoPath);
                Integer choice=resolved.get(row.videoPath);
                icon.type=checked?"check":row.eligible()&&choice==null?"caption":"blank";
                icon.p.setColor(checked?TvUi.INK:TvUi.AMBER);
                icon.setBackground(ui.shape(checked?TvUi.AMBER:0,row.eligible()?TvUi.LINE:0,4)); icon.invalidate();
                title.setText(row.episode.isEmpty()?basename(row.videoPath):row.episode);
                video.setText(basename(row.videoPath));
                status.setText(state(row));
                status.setTextColor(checked?TvUi.GOOD:row.eligible()?TvUi.AMBER:TvUi.MUTED);
                setContentDescription(row.episode+"，"+basename(row.videoPath)+"，"+status.getText());
            }
        }
    }

    private static void available(TextView button,boolean enabled) {
        button.setEnabled(enabled); button.setFocusable(enabled); button.setAlpha(enabled?1f:.45f);
    }

    static void progress(TvUi ui,int done,int total,String filename) {
        LinearLayout body=ui.body;
        body.removeAllViews();
        body.addView(ui.text("正在保存字幕   "+done+" / "+total,20,TvUi.PAPER,true));
        ui.space(body,10);
        ProgressBar progress=new ProgressBar(ui.a,null,android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(TvUi.AMBER));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(TvUi.LINE));
        progress.setMax(Math.max(1,total)); progress.setProgress(done);
        body.addView(progress,ui.size(-1,5));
        ui.space(body,10); body.addView(ui.note(filename));
    }

    static void results(TvUi ui,List<TvUi.Item> outcomes,Runnable done) {
        LinearLayout body=ui.body;
        body.removeAllViews();
        ListView list=ui.items(outcomes,i->{
            TvUi.Item row=outcomes.get(i);
            new android.app.AlertDialog.Builder(ui.a).setTitle(row.title).setMessage(row.detail)
                    .setPositiveButton("关闭",null).show();
        });
        ui.fill(body,list,-1);
        ui.space(body,8);
        TextView exit=ui.button("完成",true,done);
        LinearLayout actions=ui.row(); actions.addView(exit,ui.size(-2,38)); body.addView(actions);
        exit.requestFocus();
    }
}
