package cn.nastv.subtitles;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

final class DiscoveryPicker {
    interface Choice { void select(String address); }
    private final LanDiscovery discovery;
    private final AlertDialog dialog;
    private final ListView list;
    private final TextView status;
    private final ArrayAdapter<String> adapter;
    private final List<LanDiscovery.Device> devices=new ArrayList<>();

    DiscoveryPicker(Activity activity,TvUi ui,Choice choice,Runnable manual) {
        discovery=new LanDiscovery(activity);
        LinearLayout panel=ui.col();ui.pad(panel,22,8);
        status=ui.note("");panel.addView(status);ui.space(panel,6);
        list=new ListView(activity);panel.addView(list,ui.size(-1,144));
        adapter=new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1);
        list.setAdapter(adapter);list.setVisibility(View.GONE);
        dialog=new AlertDialog.Builder(activity).setTitle("发现局域网 NAS").setView(panel)
                .setNeutralButton("重新查找",null)
                .setNegativeButton("手动输入 IP",(d,which)->manual.run()).create();
        dialog.setOnDismissListener(d->discovery.stop());
        list.setOnItemClickListener((p,v,i,id)->{
            String address=devices.get(i).address();
            dialog.dismiss();choice.select(address);
        });
        dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->start());
            start();
        });
    }

    void show(){dialog.show();}
    void dismiss(){if(dialog.isShowing())dialog.dismiss();}

    private void start(){
        discovery.stop();
        status.setText("正在查找同一局域网中公布 SMB 服务的设备…");
        discovery.start(new LanDiscovery.Listener(){
            @Override public void onDevices(List<LanDiscovery.Device> current){
                String previous=list.getSelectedItemPosition()>=0 && list.getSelectedItemPosition()<devices.size()
                        ? devices.get(list.getSelectedItemPosition()).address() : "";
                boolean first=devices.isEmpty();
                devices.clear();devices.addAll(current);adapter.setNotifyOnChange(false);adapter.clear();
                int restore=0;
                for(int i=0;i<devices.size();i++){
                    LanDiscovery.Device device=devices.get(i);
                    adapter.add(device.name+"     "+device.address());
                    if(previous.equals(device.address()))restore=i;
                }
                adapter.notifyDataSetChanged();
                list.setVisibility(devices.isEmpty()?View.GONE:View.VISIBLE);
                if(!devices.isEmpty()){
                    status.setText("选择你的极空间，然后使用 NAS 账号登录。");
                    if(first)list.requestFocus();
                    list.setSelection(restore);
                }
            }
            @Override public void onFinished(String reason){
                if(devices.isEmpty()){
                    status.setText(reason!=null?reason:"未发现 SMB 设备。确认电视与极空间在同一局域网，并已开启 SMB。也可以手动输入极空间的内网 IP。");
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
                }
            }
        });
    }
}
