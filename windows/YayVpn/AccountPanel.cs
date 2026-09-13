using System.Diagnostics;
using System.Globalization;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
namespace YayVpn;
public partial class MainWindow {
    JsonObject account=new();
    long remainingAtSync,accountSyncedAt;
    bool deviceBusy; int devicePage;
    TextBlock? remainingText;
    readonly DispatcherTimer accountTicker=new(){Interval=TimeSpan.FromSeconds(1)};
    void StartAccountClock(){accountTicker.Tick+=(_,_)=>UpdateRemaining();accountTicker.Start();}
    void AcceptAccount(JsonObject value){account=value;remainingAtSync=Math.Max(0,(value["expires_at"]?.GetValue<long>()??0)-(value["server_time"]?.GetValue<long>()??DateTimeOffset.UtcNow.ToUnixTimeSeconds()));accountSyncedAt=Stopwatch.GetTimestamp();UpdateRemaining();}
    void UpdateRemaining(){if(remainingText==null)return;long seconds=Math.Max(0,remainingAtSync-(long)Stopwatch.GetElapsedTime(accountSyncedAt).TotalSeconds);remainingText.Text=seconds==0?T("Access expired","访问已过期","Akses kedaluwarsa"):T("Time remaining: ","剩余时间：","Sisa waktu: ")+T($"{seconds/86400}d {seconds%86400/3600:D2}h {seconds%3600/60:D2}m {seconds%60:D2}s",$"{seconds/86400}天 {seconds%86400/3600:D2}时 {seconds%3600/60:D2}分 {seconds%60:D2}秒",$"{seconds/86400}h {seconds%86400/3600:D2}j {seconds%3600/60:D2}m {seconds%60:D2}d");}
    void RemainingLabel(){remainingText=new TextBlock{FontSize=16,Foreground=Brushes.White,Margin=new(0,14,0,8),TextAlignment=TextAlignment.Center};Root.Children.Add(remainingText);UpdateRemaining();}
    Image Logo()=>new(){Source=new BitmapImage(new Uri("pack://application:,,,/Assets/yay-logo.png")),Width=30,Height=30,Margin=new(0,0,10,0)};
    void EmptyDevice(){var card=new Button{Content="+",FontSize=46,Height=94,ToolTip=T("Add another device","添加另一台设备","Tambahkan perangkat lain")};card.Click+=(_,_)=>MessageBox.Show(T("Install Yay VPN on your other device and sign in with this same account. It will fill an empty slot automatically.","在另一台设备上安装 Yay VPN 并使用同一账号登录，它将自动占用一个空位。","Pasang Yay VPN di perangkat lain dan masuk dengan akun yang sama. Satu slot kosong akan terisi otomatis."));Root.Children.Add(card);Label(T("Empty device slot","空设备位","Slot perangkat kosong"),13,Brushes.Gray);}
    async Task LoadDevices(){
        if(deviceBusy)return;
        screen="devices";Base("YAY VPN",true);Label(T("Devices & limit","设备与上限","Perangkat & batas"),24);Label(T("Loading…","正在加载…","Memuat…"));int page=devicePage;deviceBusy=true;
        try{var data=await api.Call("GET","/v1/devices");if(page!=devicePage)return;RenderDevices(data);}
        catch(ApiException e) when(e.Status==401||e.Status==403){if(page==devicePage){tunnel.Stop();api.ClearSession();Welcome();}}
        catch{if(page==devicePage){Label(T("Could not load devices. Try again.","无法加载设备，请重试。","Gagal memuat perangkat. Coba lagi."));Add(T("Retry","重试","Coba lagi"),async()=>await LoadDevices());}}
        finally{deviceBusy=false;}
    }
    void RenderDevices(JsonObject data){
        AcceptAccount(data["account"]!.AsObject());screen="devices";Base("YAY VPN",true);
        int limit=account["device_limit"]!.GetValue<int>();var devices=data["devices"]!.AsArray();
        Label(T("Devices & limit","设备与上限","Perangkat & batas"),24);Label($"{devices.Count} / {limit} "+T("slots used","个设备位已使用","slot digunakan"));
        foreach(var node in devices.OfType<JsonObject>()){
            string id=node["id"]!.GetValue<string>(),name=node["name"]!.GetValue<string>();bool current=node["is_current"]!.GetValue<bool>();
            var line=new DockPanel{Margin=new(0,7,0,7)};
            if(!current){var remove=new Button{Content=T("Remove","移除","Hapus"),MinWidth=86};DockPanel.SetDock(remove,Dock.Right);line.Children.Add(remove);remove.Click+=async(_,_)=>{
                if(deviceBusy)return;
                var prompt=T($"Remove {name}? Its session will end. If someone knows your password, ask the administrator to change it.",$"移除 {name}？该设备将退出登录。若有人知道你的密码，请联系管理员修改。",$"Hapus {name}? Sesinya akan berakhir. Jika orang lain tahu kata sandi Anda, minta admin menggantinya.");
                if(MessageBox.Show(prompt,"Yay VPN",MessageBoxButton.YesNo)!=MessageBoxResult.Yes)return;
                int page=devicePage;deviceBusy=true;remove.IsEnabled=false;
                try{var result=await api.Call("DELETE","/v1/devices/"+Uri.EscapeDataString(id));if(page==devicePage)RenderDevices(result);}
                catch(ApiException e) when(e.Status==401||e.Status==403){if(page==devicePage){tunnel.Stop();api.ClearSession();Welcome();}}
                catch{if(page==devicePage)MessageBox.Show(T("Removal failed. Refresh the device list.","移除失败，请刷新设备列表。","Gagal menghapus. Muat ulang daftar perangkat."));}
                finally{deviceBusy=false;remove.IsEnabled=true;}
                };
            }
            string lastSeen=DateTimeOffset.FromUnixTimeSeconds(node["last_seen"]!.GetValue<long>()).ToLocalTime().ToString("g",CultureInfo.GetCultureInfo(lang=="zh"?"zh-CN":lang=="id"?"id-ID":"en-US"));
            var label=new TextBlock{Text=name+"\n"+id[..8]+" · "+lastSeen+(current?"\n"+T("This device","当前设备","Perangkat ini"):""),FontSize=16,VerticalAlignment=VerticalAlignment.Center,Margin=new(10)};line.Children.Add(label);Root.Children.Add(new Border{Background=new SolidColorBrush(Color.FromRgb(17,19,23)),CornerRadius=new(12),Child=line});
        }
        for(int i=devices.Count;i<limit;i++)EmptyDevice();
        Add(T("Refresh devices","刷新设备","Muat ulang perangkat"),async()=>await LoadDevices());
    }
}
