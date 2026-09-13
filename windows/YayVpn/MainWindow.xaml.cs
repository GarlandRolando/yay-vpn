using System.IO;
using System.Diagnostics;
using System.Globalization;
using System.Net.Sockets;
using System.Net.NetworkInformation;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace YayVpn;
public partial class MainWindow : Window {
    YayApi api=null!;Tunnel tunnel=null!;JsonArray nodes=new();string lang="en";
    CancellationTokenSource? operation;bool busy;string screen="welcome";
    readonly string languageFile=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"YayVPN","language.txt");
    bool lightning;Button? lightningButton,enhancedButton;TextBlock? trafficText;
    string ModeFile=>Path.Combine(Path.GetDirectoryName(languageFile)!,"connection-mode.txt");
    ComboBox? picker;TextBlock? status,connectionHint;string lastOperationError="";Button? power;WorldMap? map;
    readonly Dictionary<string,(long ms,long at,int revision)> pings=new();
    static readonly Brush Red=new SolidColorBrush(Color.FromRgb(243,75,89)),Green=new SolidColorBrush(Color.FromRgb(76,229,160));
    string T(string en,string zh,string id)=>lang=="zh"?zh:lang=="id"?id:en;
    public MainWindow(){InitializeComponent();StartAccountClock();try{if(File.Exists(languageFile))lang=File.ReadAllText(languageFile);lightning=File.Exists(ModeFile)&&File.ReadAllText(ModeFile)=="lightning";}catch{}NetworkChange.NetworkAddressChanged+=NetworkChanged;Loaded+=async(_,_)=>{try{api=new();tunnel=new(api);tunnel.Stopped+=()=>Dispatcher.InvokeAsync(()=>{if(screen=="home")ShowHome();});if(api.Token.Length>0){try{await Refresh();}catch(ApiException e) when(e.Status==401||e.Status==403){api.ClearSession();Welcome();}catch{Welcome();}}else Welcome();}catch{MessageBox.Show("Yay VPN could not open secure storage or reach the cloud. Please restart and check your connection.");Close();}};Closed+=(_,_)=>{accountTicker.Stop();NetworkChange.NetworkAddressChanged-=NetworkChanged;operation?.Cancel();tunnel?.Dispose();api?.Dispose();};}
    void NetworkChanged(object? sender,EventArgs e){Dispatcher.InvokeAsync(()=>{pings.Clear();});}
    void Base(string title,bool back=false){devicePage++;remainingText=null;trafficText=null;connectionHint=null;Root.Children.Clear();var bar=new DockPanel();var b=new Button{Content=back?"←":"⚙",Width=52};b.Click+=(_,_)=>{if(back){if(api.Token.Length>0)ShowHome();else Welcome();}else Settings();};DockPanel.SetDock(b,Dock.Left);bar.Children.Add(b);var brand=new StackPanel{Orientation=Orientation.Horizontal,HorizontalAlignment=HorizontalAlignment.Center,Margin=new(0,12,40,20)};brand.Children.Add(Logo());brand.Children.Add(new TextBlock{Text=title,FontSize=25,FontWeight=FontWeights.Bold});bar.Children.Add(brand);Root.Children.Add(bar);}
    void Label(string value,int size=16,Brush? color=null){Root.Children.Add(new TextBlock{Text=value,FontSize=size,Foreground=color??Brushes.White,Margin=new(0,12,0,12)});}
    Button Add(string label,Action action,bool primary=false){var b=new Button{Content=label};if(primary){b.Background=Red;b.Foreground=Brushes.Black;}b.Click+=(_,_)=>action();Root.Children.Add(b);return b;}
    void Welcome(){screen="welcome";Base("YAY VPN");Root.Children.Add(new WorldMap{Height=230});Label(T("Your world. Connected.","连接你的世界。","Duniamu. Terhubung."),30);Add(T("Log in","登录","Masuk"),Login,true);Add(T("Sign up","注册","Daftar"),Contacts);}
    void Login(){screen="login";Base("YAY VPN",true);Label(T("Login ID","账号","ID masuk"));var user=new TextBox{FontSize=18,Padding=new(12)};Root.Children.Add(user);Label(T("Password","密码","Kata sandi"));var password=new PasswordBox{FontSize=18,Padding=new(12)};Root.Children.Add(password);Button? button=null;button=Add(T("Log in","登录","Masuk"),async()=>{button!.IsEnabled=false;try{await api.Login(user.Text.Trim(),password.Password);password.Clear();await Refresh();}catch{MessageBox.Show(T("Sign-in failed. Check your credentials, device limit and access expiry.","登录失败，请检查账号密码、设备上限和有效期。","Masuk gagal. Periksa akun, batas perangkat, dan masa akses."));button.IsEnabled=true;}},true);}
    async Task Refresh(){var b=await api.Call("GET","/v1/bootstrap");nodes=b["servers"]!.AsArray();AcceptAccount(b["account"]!.AsObject());pings.Clear();ShowHome();}
    void Contacts(){screen="contacts";Base(T("Sign up / Help","注册 / 帮助","Daftar / Bantuan"),true);Label(T("Contact us to receive a login ID and password. Your administrator sets the device limit and access period.","联系我们获取账号密码。管理员将设置设备上限和有效期。","Hubungi kami untuk mendapatkan akun. Admin menetapkan batas perangkat dan masa akses."));Add("Weixin: wxid_3lt1aad36ai822",()=>{Clipboard.SetText("wxid_3lt1aad36ai822");MessageBox.Show(T("Weixin ID copied","微信号已复制","ID Weixin disalin"));});Add("WhatsApp: +371 28 635 209",()=>Open("https://wa.me/37128635209"));Add("Email: dummystorage22@gmail.com",()=>Open("mailto:dummystorage22@gmail.com"));}
    static void Open(string url){try{Process.Start(new ProcessStartInfo(url){UseShellExecute=true});}catch{MessageBox.Show("Open the contact address manually.");}}
    void Settings(){if(busy)return;screen="settings";Base(T("Settings","设置","Pengaturan"),true);Label("Yay VPN "+typeof(MainWindow).Assembly.GetName().Version!.ToString(3),13,Brushes.Gray);Add(T("Help","帮助","Bantuan"),Contacts);if(api.Token.Length>0)Add(T("Devices & limit","设备与上限","Perangkat & batas"),async()=>await LoadDevices());var language=new ComboBox{ItemsSource=new[]{"English","中文","Bahasa Indonesia"},SelectedIndex=lang=="zh"?1:lang=="id"?2:0,Margin=new(0,20,0,20)};language.SelectionChanged+=(_,_)=>{lang=new[]{"en","zh","id"}[language.SelectedIndex];try{File.WriteAllText(languageFile,lang);}catch{}Settings();};Root.Children.Add(language);if(api.Token.Length>0)Add(T("Log out","退出登录","Keluar"),async()=>{operation?.Cancel();tunnel.Stop();try{await api.Logout();}catch{}nodes=new();pings.Clear();Welcome();});}
    List<JsonObject> Country(string country)=>nodes.OfType<JsonObject>().Where(n=>n["location"]?.GetValue<string>()==country).ToList();
    void ShowHome(){screen="home";Base("YAY VPN");var countries=nodes.OfType<JsonObject>().Select(n=>n["location"]?.GetValue<string>()??"").Distinct().Order().ToArray();string selected=(picker?.SelectedItem as ComboBoxItem)?.Tag as string??(countries.Contains("Singapore")?"Singapore":countries.FirstOrDefault()??"");picker=new ComboBox{FontSize=19,Padding=new(12),IsEnabled=!tunnel.Connected&&!busy};foreach(var country in countries){var item=new ComboBoxItem{Content=CountryLabel(country),Tag=country};picker.Items.Add(item);if(country==selected)picker.SelectedItem=item;}Root.Children.Add(picker);var expander=new Expander{Header=T("Country pings ▾","国家延迟 ▾","Ping negara ▾"),Foreground=Brushes.White,Margin=new(0,12,0,0)};var list=new StackPanel();foreach(var country in countries){var measured=Country(country).Select(n=>Fresh(n)).Where(n=>n>=0).ToArray();list.Children.Add(new TextBlock{Text=CountryTitle(country)+"   "+(measured.Length>0?measured.Min()+" ms":"—"),Margin=new(6),Foreground=Brushes.White});}expander.Content=list;Root.Children.Add(expander);
        Add(T("Test country pings","测试国家延迟","Uji ping negara"),async()=>{if(busy||tunnel.Connected)return;await Run(async ct=>{await Measure(nodes.OfType<JsonObject>().ToList(),ct,60000);ShowHome();});});
        var hero=new Grid{Height=310};map=new WorldMap{Active=tunnel.Connected};hero.Children.Add(map);power=new Button{Style=(Style)FindResource("PowerButton"),Content="⏻",FontSize=70,Width=180,Height=180,Background=Brushes.Black,Foreground=tunnel.Connected?Green:Red,BorderBrush=tunnel.Connected?Green:Red,BorderThickness=new(2)};power.Click+=async(_,_)=>{
            if(busy){operation?.Cancel();tunnel.Stop();if(status!=null)status.Text=T("Cancelling…","正在取消…","Membatalkan…");return;}
            if(tunnel.Connected){tunnel.Stop();lastOperationError="";ShowHome();return;}
            string country=(picker.SelectedItem as ComboBoxItem)?.Tag as string??"";
            await Run(ct=>ConnectCountry(country,ct));
        };hero.Children.Add(power);Root.Children.Add(hero);ConnectionModes();
        status=new TextBlock{Text=tunnel.Connected?T("Connected","已连接","Terhubung"):T("Disconnected","未连接","Terputus"),FontSize=26,TextAlignment=TextAlignment.Center,Foreground=tunnel.Connected?Green:Brushes.White};Root.Children.Add(status);connectionHint=new TextBlock{TextWrapping=TextWrapping.Wrap,TextAlignment=TextAlignment.Center,Foreground=Brushes.Gray,Margin=new(0,8,0,8)};Root.Children.Add(connectionHint);Label(T("TCP ping is a reachability measurement. VPN access is checked separately on connection.","TCP 延迟用于测试可达性，连接时另行验证 VPN 网络访问。","Ping TCP mengukur keterjangkauan. Akses VPN diuji terpisah saat tersambung."),12,Brushes.Gray);RemainingLabel();TrafficPanel();Add(T("Refresh locations","刷新地区","Muat ulang lokasi"),async()=>{if(!busy&&!tunnel.Connected)await Run(async _=>await Refresh());});}
    void ConnectionModes(){
        var row=new Grid{Margin=new(0,0,0,8)};row.ColumnDefinitions.Add(new());row.ColumnDefinitions.Add(new());
        lightningButton=new Button{Content=new TextBlock{Text=T("Lightning connection","极速连接","Koneksi kilat"),TextWrapping=TextWrapping.Wrap,TextAlignment=TextAlignment.Center,FontSize=14,Foreground=lightning?Brushes.Black:Brushes.White},Background=lightning?Red:new SolidColorBrush(Color.FromRgb(25,27,32)),Foreground=lightning?Brushes.Black:Brushes.White,IsEnabled=!busy&&!tunnel.Connected,Margin=new(0,0,5,0)};
        enhancedButton=new Button{Content=new TextBlock{Text=T("Enhanced connection","优选连接","Koneksi optimal"),TextWrapping=TextWrapping.Wrap,TextAlignment=TextAlignment.Center,FontSize=14,Foreground=!lightning?Brushes.Black:Brushes.White},Background=!lightning?Red:new SolidColorBrush(Color.FromRgb(25,27,32)),Foreground=!lightning?Brushes.Black:Brushes.White,IsEnabled=!busy&&!tunnel.Connected,Margin=new(5,0,0,0)};
        lightningButton.Click+=(_,_)=>SetMode(true);enhancedButton.Click+=(_,_)=>SetMode(false);Grid.SetColumn(enhancedButton,1);row.Children.Add(lightningButton);row.Children.Add(enhancedButton);Root.Children.Add(row);
        Root.Children.Add(new TextBlock{Text=lightning?T("Random server in your country. Skips the ping scan.","随机选择当前国家的服务器，跳过延迟扫描。","Server acak di negara pilihan. Tanpa pemindaian ping."):T("Tests for up to 12 seconds, then uses the fastest measured options.","最多测试12秒，然后选择实测较快的服务器。","Menguji hingga 12 detik, lalu memilih server terukur tercepat."),FontSize=13,Foreground=Brushes.Gray,TextAlignment=TextAlignment.Center,TextWrapping=TextWrapping.Wrap,Margin=new(0,4,0,18)});
    }
    void SetMode(bool fast){if(busy||tunnel.Connected)return;lightning=fast;try{Directory.CreateDirectory(Path.GetDirectoryName(ModeFile)!);File.WriteAllText(ModeFile,fast?"lightning":"enhanced");}catch{}ShowHome();}
    static string Rate(long bytes)=>bytes<1024?$"{bytes} B/s":bytes<1048576?$"{bytes/1024.0:F1} KB/s":$"{bytes/1048576.0:F1} MB/s";
    void TrafficPanel(){
        trafficText=new TextBlock{FontSize=17,TextAlignment=TextAlignment.Center,FontFamily=new FontFamily("Consolas"),Foreground=Brushes.White,TextWrapping=TextWrapping.Wrap};
        Root.Children.Add(new Border{Background=new SolidColorBrush(Color.FromRgb(17,19,23)),CornerRadius=new(12),Padding=new(15),Margin=new(0,5,0,5),Child=trafficText});
        Root.Children.Add(new TextBlock{Text=T("Live traffic · VPN latency (updated every 15s)","实时流量 · VPN 延迟（每15秒更新）","Trafik langsung · latensi VPN (setiap 15 dtk)"),FontSize=12,Foreground=Brushes.Gray,TextAlignment=TextAlignment.Center,TextWrapping=TextWrapping.Wrap,Margin=new(0,0,0,12)});UpdateTraffic();
    }
    void UpdateTraffic(){
        if(trafficText==null||tunnel==null)return;bool on=tunnel.Connected;var sample=tunnel.Telemetry?.Traffic;var ping=tunnel.Telemetry?.Latency;long now=Environment.TickCount64;
        bool fresh=on&&sample!=null&&now-sample.At<4000;string down=!on?"0 B/s":fresh?Rate(sample!.Down):"—",up=!on?"0 B/s":fresh?Rate(sample!.Up):"—";
        string ms=on&&tunnel.InternetHealthy&&ping!=null&&now-ping.At<30000?$"{ping.Millis} ms":"— ms";
        trafficText.Text=$"↓ {down}    ↑ {up}\n"+T("VPN latency: ","VPN 延迟：","Latensi VPN: ")+ms;
        if(!busy&&screen=="home"){
            bool healthy=on&&tunnel.InternetHealthy;
            if(status!=null){status.Text=healthy?T("Connected","已连接","Terhubung"):on?T("VPN active","VPN 已启动","VPN aktif"):T("Disconnected","未连接","Terputus");status.Foreground=healthy?Green:Brushes.White;}
            if(connectionHint!=null)connectionHint.Text=on&&!healthy?T("Internet check unavailable. Try a website, or tap to disconnect.","网络检查不可用。请尝试打开网页，或点击断开。","Pemeriksaan internet tidak tersedia. Coba buka situs, atau ketuk untuk memutuskan."):on?T("Tap to disconnect","点击断开连接","Ketuk untuk memutuskan"):lastOperationError.Length>0?lastOperationError:tunnel.LastError;
            if(map!=null){map.Active=healthy;map.InvalidateVisual();}
            if(power!=null){power.Foreground=healthy?Green:Red;power.BorderBrush=healthy?Green:Red;}
        }
    }
    static readonly Dictionary<string,string> CountryCodes=new(){["Singapore"]="SG",["Japan"]="JP",["Hong Kong"]="HK",["United States"]="US",["United Kingdom"]="GB",["Australia"]="AU",["Germany"]="DE",["Spain"]="ES",["Brazil"]="BR",["India"]="IN",["South Africa"]="ZA",["South Korea"]="KR"};
    string CountryTitle(string country){return lang=="en"?country:Translations.TryGetValue(country,out var names)?(lang=="zh"?names.zh:names.id):country;}
    static readonly Dictionary<string,(string zh,string id)> Translations=new(){["Singapore"]=("新加坡","Singapura"),["Japan"]=("日本","Jepang"),["Hong Kong"]=("香港","Hong Kong"),["United States"]=("美国","Amerika Serikat"),["United Kingdom"]=("英国","Britania Raya"),["Australia"]=("澳大利亚","Australia"),["Germany"]=("德国","Jerman"),["Spain"]=("西班牙","Spanyol"),["Brazil"]=("巴西","Brasil"),["India"]=("印度","India"),["South Africa"]=("南非","Afrika Selatan"),["South Korea"]=("韩国","Korea Selatan")};
    StackPanel CountryLabel(string country){var panel=new StackPanel{Orientation=Orientation.Horizontal};panel.Children.Add(new FlagView{Code=CountryCodes.GetValueOrDefault(country,""),Width=30,Height=20,Margin=new(0,0,10,0)});panel.Children.Add(new TextBlock{Text=CountryTitle(country),Foreground=Brushes.Black});return panel;}
    long Fresh(JsonObject node){string id=node["id"]!.GetValue<string>();return pings.TryGetValue(id,out var p)&&Environment.TickCount64-p.at<120000&&p.revision==node["revision"]!.GetValue<int>()?p.ms:-1;}
    async Task ConnectCountry(string country,CancellationToken ct){
        var group=Country(country);List<string> ordered;
        if(lightning)ordered=ConnectionPolicy.Lightning(group.Select(n=>n["id"]!.GetValue<string>()).ToList(),Random.Shared);
        else{
            if(!group.Any(n=>Fresh(n)>=0))await Measure(group,ct,12000);
            ordered=ConnectionPolicy.Enhanced(group.ToDictionary(n=>n["id"]!.GetValue<string>(),n=>Fresh(n)),Random.Shared);
        }
        ct.ThrowIfCancellationRequested();
        if(ordered.Count==0)throw new IOException("No measured server is available. Try Lightning or another country.");
        if(status!=null)status.Text=T("Connecting… Tap to cancel.","正在连接…点击取消。","Menghubungkan… Ketuk untuk batal.");
        using var budget=CancellationTokenSource.CreateLinkedTokenSource(ct);budget.CancelAfter(45000);
        Exception? last=null;
        try{foreach(var id in ordered){
            budget.Token.ThrowIfCancellationRequested();
            try{await tunnel.Connect(id,budget.Token);last=null;break;}
            catch(OperationCanceledException){throw;}
            catch(ApiException e) when(e.Status==401||e.Status==403){throw;}
            catch(Exception e){last=e;}
        }}catch(OperationCanceledException) when(!ct.IsCancellationRequested&&budget.IsCancellationRequested){throw new TimeoutException("Connection timed out. Try another country or network.");}
        if(last!=null)throw last;
    }
    async Task Measure(List<JsonObject> group,CancellationToken ct,int budgetMs){
        using var budget=CancellationTokenSource.CreateLinkedTokenSource(ct);budget.CancelAfter(budgetMs);var work=budget.Token;
        using var gate=new SemaphoreSlim(6);int done=0;ApiException? denied=null;
        try{await Task.WhenAll(group.Select(async node=>{
            await gate.WaitAsync(work);
            try{
                long ms=-1;
                try{
                    using var request=CancellationTokenSource.CreateLinkedTokenSource(work);request.CancelAfter(4000);
                    var grant=await api.Call("POST","/v1/connect",new(){["server_id"]=node["id"]!.GetValue<string>()},request.Token);
                    var outbound=grant["config"]!["outbounds"]![0]!;using var socket=new TcpClient();var watch=Stopwatch.StartNew();
                    using var timeout=CancellationTokenSource.CreateLinkedTokenSource(work);timeout.CancelAfter(1500);
                    await socket.ConnectAsync(outbound["server"]!.GetValue<string>(),outbound["server_port"]!.GetValue<int>(),timeout.Token);ms=Math.Max(1,watch.ElapsedMilliseconds);
                }catch(ApiException e) when(e.Status==401||e.Status==403){denied=e;budget.Cancel();throw;}
                catch(Exception) when(!work.IsCancellationRequested){}
                work.ThrowIfCancellationRequested();pings[node["id"]!.GetValue<string>()]=(ms,Environment.TickCount64,node["revision"]!.GetValue<int>());
                if(status!=null)status.Text=$"{++done} / {group.Count} · "+T("Tap to cancel","点击取消","Ketuk untuk batal");
            }finally{gate.Release();}
        }));}catch(OperationCanceledException) when(!ct.IsCancellationRequested&&budget.IsCancellationRequested){}
        if(denied!=null)throw denied;ct.ThrowIfCancellationRequested();
    }
    async Task Run(Func<CancellationToken,Task> action){
        if(busy)return;busy=true;lastOperationError="";operation=new();
        if(picker!=null)picker.IsEnabled=false;if(lightningButton!=null)lightningButton.IsEnabled=false;if(enhancedButton!=null)enhancedButton.IsEnabled=false;
        if(status!=null)status.Text=lightning?T("Connecting… Tap to cancel.","正在连接…点击取消。","Menghubungkan… Ketuk untuk batal."):T("Testing for up to 12 seconds. Tap to cancel.","最多测试12秒。点击取消。","Menguji hingga 12 detik. Ketuk untuk batal.");
        try{await action(operation.Token);}
        catch(OperationCanceledException) when(operation.IsCancellationRequested){tunnel.Stop();}
        catch(Exception error){
            tunnel.Stop();lastOperationError=error is ApiException a?$"Access check failed (HTTP {a.Status}). Sign in again or contact support.":error is IOException or TimeoutException?error.Message:T("Connection failed. Try another country or network.","连接失败，请尝试其他国家或网络。","Koneksi gagal. Coba negara atau jaringan lain.");
        }finally{busy=false;operation.Dispose();operation=null;if(screen=="home")ShowHome();}
    }
}
