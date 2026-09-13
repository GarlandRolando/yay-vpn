package com.yay.vpn;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BG=0xff000000,CARD=0xff111317,INK=0xfff4f5f7,MUTED=0xff979da8,RED=0xfff34b59,GREEN=0xff4ce5a0;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private Api api;private LatencyProbe probes;private LinearLayout root,countryPanel;private TextView stateTitle,stateHint,timer,serverLabel,progress;
    private TextView remainingText;private long remainingAtSync,accountSyncedAt;private int devicePage;private boolean deviceBusy;
    private boolean lightning;private Button lightningButton,enhancedButton;private TextView modeHint,trafficText;
    private PowerButton power;private WorldMapView map;private Button scanButton;
    private JSONObject account=new JSONObject();private JSONArray servers=new JSONArray();
    private List<CountryCatalog.Country> countries=new ArrayList<>();private CountryCatalog.Country selected;
    private final Map<String,TextView> countryPings=new HashMap<>();
    private String language="en",screen="welcome";private boolean visible,loading,expanded,choosing,loggingOut;
    private ArrayList<String> pendingIds=new ArrayList<>();
    private final Runnable ticker=new Runnable(){public void run(){if(visible&&screen.equals("home"))updateStatus();if(visible)handler.postDelayed(this,1000);}};
    private String t(String en,String zh,String id){return language.equals("zh")?zh:language.equals("id")?id:en;}
    @Override public void onCreate(Bundle bundle){
        super.onCreate(bundle);
        language=getSharedPreferences("display",0).getString("language","en");
        lightning=getSharedPreferences("display",0).getBoolean("lightning",false);
        try{api=new Api(this);probes=new LatencyProbe(this,api);if(api.store.get("token").isEmpty())welcome();else{readCache();home();}}
        catch(Exception e){new AlertDialog.Builder(this).setTitle("Yay VPN").setMessage(t("Secure storage is unavailable. Restart your phone and try again.","安全存储不可用，请重启手机后重试。","Penyimpanan aman tidak tersedia. Mulai ulang ponsel dan coba lagi.")).setPositiveButton("OK",(d,w)->finish()).show();}
    }
    @Override protected void onResume(){super.onResume();visible=true;handler.removeCallbacks(ticker);handler.post(ticker);if(screen.equals("home"))refresh();}
    @Override protected void onPause(){visible=false;handler.removeCallbacks(ticker);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(ticker);if(probes!=null)probes.close();io.shutdownNow();super.onDestroy();}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private void gap(LinearLayout p,int size){p.addView(new Space(this),new LinearLayout.LayoutParams(1,dp(size)));}
    private void centered(LinearLayout p,TextView v){v.setGravity(Gravity.CENTER);p.addView(v,new LinearLayout.LayoutParams(-1,-2));}
    private Button button(String label,boolean primary){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(primary?Color.BLACK:INK);b.setTextSize(15);b.setBackground(shape(primary?RED:CARD,16));b.setPadding(dp(16),dp(8),dp(16),dp(8));b.setMinHeight(dp(52));return b;}
    private void action(LinearLayout p,String label,boolean primary,Runnable run){Button b=button(label,primary);p.addView(b,new LinearLayout.LayoutParams(-1,dp(56)));b.setOnClickListener(v->run.run());gap(p,12);}
    private void base(String name){devicePage++;remainingText=null;trafficText=null;screen=name;ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);root=column();root.setPadding(dp(24),dp(20),dp(24),dp(24));scroll.addView(root,new ScrollView.LayoutParams(-1,-1));setContentView(scroll);scroll.setOnApplyWindowInsetsListener((v,i)->{root.setPadding(dp(24),dp(20)+i.getSystemWindowInsetTop(),dp(24),dp(24)+i.getSystemWindowInsetBottom());return i;});scroll.requestApplyInsets();}
    private void header(boolean back,String title){LinearLayout r=row();Button left=button(back?"←":"⚙",false);left.setContentDescription(back?t("Back","返回","Kembali"):t("Settings","设置","Pengaturan"));r.addView(left,new LinearLayout.LayoutParams(dp(52),dp(48)));left.setOnClickListener(v->{if(back){if(hasSession())home();else welcome();}else settings();});LinearLayout brand=row();brand.setGravity(Gravity.CENTER);ImageView mark=new ImageView(this);mark.setImageResource(R.drawable.ic_yay);brand.addView(mark,new LinearLayout.LayoutParams(dp(28),dp(28)));TextView logo=text("  "+title,21,INK,true);brand.addView(logo);r.addView(brand,new LinearLayout.LayoutParams(0,-2,1));r.addView(new Space(this),new LinearLayout.LayoutParams(dp(52),1));root.addView(r);gap(root,24);}
    private boolean hasSession(){try{return !api.store.get("token").isEmpty();}catch(Exception e){return false;}}
    private void welcome(){base("welcome");header(false,"YAY VPN");gap(root,32);FrameLayout hero=new FrameLayout(this);WorldMapView m=new WorldMapView(this);hero.addView(m,new FrameLayout.LayoutParams(-1,-1));TextView logo=text("YAY",70,INK,true);logo.setGravity(Gravity.CENTER);hero.addView(logo,new FrameLayout.LayoutParams(-1,-1));root.addView(hero,new LinearLayout.LayoutParams(-1,dp(260)));centered(root,text(t("Your world. Connected.","连接你的世界。","Duniamu. Terhubung."),28,INK,true));gap(root,12);centered(root,text(t("One account. A world of locations.","一个账号，连接全球。","Satu akun. Pilihan lokasi di seluruh dunia."),14,MUTED,false));gap(root,34);action(root,t("Log in","登录","Masuk"),true,()->login());action(root,t("Sign up","注册","Daftar"),false,()->contacts(true));action(root,"English / 中文 / Indonesia",false,()->languageDialog());}
    private EditText field(String label,boolean password){root.addView(text(label,13,MUTED,true));gap(root,8);EditText f=new EditText(this);f.setSingleLine();f.setTextSize(17);f.setTextColor(INK);f.setBackground(shape(CARD,14));f.setPadding(dp(16),0,dp(16),0);f.setInputType(InputType.TYPE_CLASS_TEXT|(password?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));f.setAutofillHints(password?View.AUTOFILL_HINT_PASSWORD:View.AUTOFILL_HINT_USERNAME);root.addView(f,new LinearLayout.LayoutParams(-1,dp(56)));gap(root,22);return f;}
    private void login(){base("login");header(true,"YAY VPN");root.addView(text(t("Welcome back.","欢迎回来。","Selamat datang kembali."),32,INK,true));gap(root,12);root.addView(text(t("Enter the credentials provided by your administrator.","输入管理员提供的账号和密码。","Masukkan akun yang diberikan administrator."),14,MUTED,false));gap(root,32);EditText username=field(t("Login ID","账号","ID masuk"),false),password=field(t("Password","密码","Kata sandi"),true);TextView error=text("",13,RED,false);root.addView(error);Button submit=button(t("Log in →","登录 →","Masuk →"),true);root.addView(submit,new LinearLayout.LayoutParams(-1,dp(56)));submit.setOnClickListener(v->{String u=username.getText().toString().trim(),p=password.getText().toString();if(u.isEmpty()||p.isEmpty()){error.setText(t("Enter both fields.","请填写账号和密码。","Isi kedua kolom."));return;}submit.setEnabled(false);error.setText(t("Signing in…","正在登录…","Sedang masuk…"));io.execute(()->{try{JSONObject result=api.call("POST","/v1/login",new JSONObject().put("username",u).put("password",p).put("public_key",api.store.publicKey()).put("device_name",Build.MANUFACTURER+" "+Build.MODEL));api.store.put("token",result.getString("token"));JSONObject fresh=api.refresh();runOnUiThread(()->{if(isDestroyed())return;acceptAccount(fresh.optJSONObject("account"),true);servers=fresh.optJSONArray("servers");password.setText("");home();if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12);});}catch(Exception ex){runOnUiThread(()->{if(isDestroyed())return;error.setText(errorMessage(ex));submit.setEnabled(true);});}});});gap(root,18);action(root,t("Need an account? Sign up","没有账号？注册","Belum punya akun? Daftar"),false,()->contacts(true));}
    private void contacts(boolean signup){base(signup?"signup":"help");header(true,t(signup?"Sign up":"Help",signup?"注册":"帮助",signup?"Daftar":"Bantuan"));root.addView(text(t(signup?"Get your Yay account.":"How can we help?",signup?"获取你的 Yay 账号。":"需要什么帮助？",signup?"Dapatkan akun Yay.":"Perlu bantuan?"),28,INK,true));gap(root,16);root.addView(text(t("Contact us using any option below. We will create your login ID and password and confirm your device limit and access period.","通过以下任一方式联系我们。我们将创建账号和密码，并确认设备数量和有效期。","Hubungi kami melalui salah satu opsi berikut. Kami akan membuat ID dan kata sandi serta mengonfirmasi batas perangkat dan masa akses."),15,MUTED,false));gap(root,28);action(root,"Weixin / 微信\nwxid_3lt1aad36ai822",false,()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Weixin ID","wxid_3lt1aad36ai822"));toast(t("Weixin ID copied. Add this ID in Weixin.","微信号已复制，请在微信中添加。","ID Weixin disalin. Tambahkan ID ini di Weixin."));});action(root,"WhatsApp\n+371 28 635 209",false,()->open("https://wa.me/37128635209"));action(root,"Email\ndummystorage22@gmail.com",false,()->open("mailto:dummystorage22@gmail.com"));gap(root,8);root.addView(text(t("Already have your credentials? Return and log in.","已有账号？返回登录。","Sudah punya akun? Kembali dan masuk."),14,MUTED,false));}
    private void open(String uri){try{startActivity(new Intent(uri.startsWith("mailto:")?Intent.ACTION_SENDTO:Intent.ACTION_VIEW,Uri.parse(uri)));}catch(ActivityNotFoundException e){toast(t("No app can open this contact. Copy the details above.","未找到可打开此联系方式的应用，请复制上方信息。","Tidak ada aplikasi untuk membuka kontak ini. Salin detail di atas."));}}
    private void settings(){choosing=false;probes.cancel();base("settings");header(true,t("Settings","设置","Pengaturan"));root.addView(text("Yay VPN "+BuildConfig.VERSION_NAME,13,MUTED,false));gap(root,12);action(root,t("Help & contact","帮助与联系","Bantuan & kontak"),false,()->contacts(false));action(root,t("Language","语言","Bahasa"),false,()->languageDialog());if(hasSession())action(root,t("Devices & limit","设备与上限","Perangkat & batas"),false,()->devices());action(root,t("Log out","退出登录","Keluar"),false,()->logout());gap(root,20);root.addView(text(t("Access until: ","有效期至：","Akses hingga: ")+expiry(),14,MUTED,false));gap(root,12);root.addView(text(t("Registered devices: ","已注册设备：","Perangkat terdaftar: ")+account.optInt("devices_used")+" / "+account.optInt("device_limit"),14,MUTED,false));gap(root,20);root.addView(text(t("Open Devices & limit to remove another device. Uninstalling alone does not free its slot.","打开设备与上限即可移除其他设备。仅卸载应用不会释放设备位。","Buka Perangkat & batas untuk menghapus perangkat lain. Menghapus aplikasi saja tidak membebaskan slot."),13,MUTED,false));}
    private void languageDialog(){String[] labels={"English","中文","Bahasa Indonesia"},codes={"en","zh","id"};int at=language.equals("zh")?1:language.equals("id")?2:0;new AlertDialog.Builder(this).setTitle(t("Language","语言","Bahasa")).setSingleChoiceItems(labels,at,(d,n)->{language=codes[n];getSharedPreferences("display",0).edit().putString("language",language).apply();d.dismiss();if(hasSession())settings();else welcome();}).setNegativeButton(t("Cancel","取消","Batal"),null).show();}
    private void readCache(){try{String raw=api.store.get("bootstrap");if(!raw.isEmpty()){JSONObject b=new JSONObject(raw);acceptAccount(b.getJSONObject("account"),false);servers=b.getJSONArray("servers");}}catch(Exception ignored){}}
    private void regroup(){String previous=selected==null?"":selected.code+selected.fallback;countries=CountryCatalog.group(servers==null?new JSONArray():servers);selected=null;for(CountryCatalog.Country c:countries)if((c.code+c.fallback).equals(previous))selected=c;if(selected==null)for(CountryCatalog.Country c:countries)if(c.code.equals("SG"))selected=c;if(selected==null&&!countries.isEmpty())selected=countries.get(0);}
    private void home(){regroup();base("home");header(false,"YAY VPN");serverLabel=text("",17,INK,true);serverLabel.setPadding(dp(18),dp(18),dp(18),dp(18));serverLabel.setBackground(shape(CARD,16));serverLabel.setFocusable(true);serverLabel.setClickable(true);root.addView(serverLabel,new LinearLayout.LayoutParams(-1,-2));serverLabel.setOnClickListener(v->{if(!YayVpnService.state.equals("OFF")||choosing){toast(t("Disconnect before changing country.","请先断开连接再切换国家。","Putuskan koneksi sebelum mengganti negara."));return;}expanded=!expanded;countryPanel.setVisibility(expanded?View.VISIBLE:View.GONE);});countryPanel=column();countryPanel.setPadding(dp(12),dp(12),dp(12),dp(12));countryPanel.setBackground(shape(CARD,16));root.addView(countryPanel);buildCountries();countryPanel.setVisibility(expanded?View.VISIBLE:View.GONE);gap(root,8);
        FrameLayout hero=new FrameLayout(this);map=new WorldMapView(this);hero.addView(map,new FrameLayout.LayoutParams(-1,-1));power=new PowerButton();FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(dp(200),dp(200),Gravity.CENTER);hero.addView(power,pp);root.addView(hero,new LinearLayout.LayoutParams(-1,dp(300)));power.setOnClickListener(v->toggle());connectionModes();stateTitle=text("",27,INK,true);centered(root,stateTitle);gap(root,10);stateHint=text("",14,MUTED,false);centered(root,stateHint);gap(root,20);timer=text("00:00:00",25,MUTED,true);timer.setTypeface(Typeface.MONOSPACE);centered(root,timer);gap(root,22);remainingText=text("",15,INK,true);centered(root,remainingText);gap(root,12);trafficText=text("",15,INK,true);trafficText.setTypeface(Typeface.MONOSPACE);trafficText.setPadding(dp(12),dp(16),dp(12),dp(16));trafficText.setBackground(shape(CARD,14));centered(root,trafficText);gap(root,8);centered(root,text(t("Live traffic · VPN latency (updated every 15s)","实时流量 · VPN 延迟（每15秒更新）","Trafik langsung · latensi VPN (setiap 15 dtk)"),12,MUTED,false));gap(root,14);action(root,t("Refresh locations","刷新地区","Muat ulang lokasi"),false,()->refresh());centered(root,text(t("PRIVATE ACCESS / YAY VPN","专属连接 / YAY VPN","AKSES PRIBADI / YAY VPN"),10,MUTED,true));updateStatus();}
    private void connectionModes(){
        LinearLayout modes=row();lightningButton=button(t("Lightning","极速","Kilat"),false);enhancedButton=button(t("Enhanced","优选","Optimal"),false);
        modes.addView(lightningButton,new LinearLayout.LayoutParams(0,dp(54),1));modes.addView(new Space(this),new LinearLayout.LayoutParams(dp(8),1));modes.addView(enhancedButton,new LinearLayout.LayoutParams(0,dp(54),1));root.addView(modes);
        lightningButton.setContentDescription(t("Lightning connection","极速连接","Koneksi kilat"));enhancedButton.setContentDescription(t("Enhanced connection","优选连接","Koneksi optimal"));
        lightningButton.setOnClickListener(v->setMode(true));enhancedButton.setOnClickListener(v->setMode(false));
        modeHint=text("",13,MUTED,false);gap(root,8);centered(root,modeHint);gap(root,18);updateModes();
    }
    private void setMode(boolean fast){if(choosing||!YayVpnService.state.equals("OFF"))return;probes.cancel();lightning=fast;getSharedPreferences("display",0).edit().putBoolean("lightning",fast).apply();updateStatus();}
    private void updateModes(){
        if(lightningButton==null||enhancedButton==null)return;boolean enabled=!choosing&&YayVpnService.state.equals("OFF");
        lightningButton.setEnabled(enabled);enhancedButton.setEnabled(enabled);
        lightningButton.setBackground(shape(lightning?RED:CARD,16));lightningButton.setTextColor(lightning?Color.BLACK:INK);
        enhancedButton.setBackground(shape(lightning?CARD:RED,16));enhancedButton.setTextColor(lightning?INK:Color.BLACK);
        modeHint.setText(lightning?t("Random server in your country. Skips the ping scan.","随机选择当前国家的服务器，跳过延迟扫描。","Server acak di negara pilihan. Tanpa pemindaian ping."):t("Tests for up to 12 seconds, then uses the fastest measured options.","最多测试12秒，然后选择实测较快的服务器。","Menguji hingga 12 detik, lalu memilih server terukur tercepat."));
    }
    private static String rate(long bytes){return bytes<1024?bytes+" B/s":bytes<1048576?String.format(Locale.ROOT,"%.1f KB/s",bytes/1024.0):String.format(Locale.ROOT,"%.1f MB/s",bytes/1048576.0);}
    private void updateTelemetry(){
        if(trafficText==null)return;boolean on=YayVpnService.state.equals("ON");TunnelTelemetry monitor=YayVpnService.telemetry;
        TunnelTelemetry.Rates rates=monitor==null?null:monitor.rates;TunnelTelemetry.Delay delay=monitor==null?null:monitor.delay;long now=SystemClock.elapsedRealtime();
        boolean fresh=on&&rates!=null&&now-rates.at<4000;String down=!on?"0 B/s":fresh?rate(rates.down):"—",up=!on?"0 B/s":fresh?rate(rates.up):"—";
        String ms=on&&YayVpnService.internetHealthy&&delay!=null&&now-delay.at<30000?delay.millis+" ms":"— ms";
        trafficText.setText("↓ "+down+"    ↑ "+up+"\n"+t("VPN latency: ","VPN 延迟：","Latensi VPN: ")+ms);
    }
    private void buildCountries(){countryPanel.removeAllViews();countryPings.clear();for(CountryCatalog.Country c:countries){LinearLayout line=row();line.setPadding(dp(8),dp(14),dp(8),dp(14));TextView label=text(c.flag()+"  "+c.label(language),16,INK,true);line.addView(label,new LinearLayout.LayoutParams(0,-2,1));TextView ping=text("—",13,GREEN,false);line.addView(ping);countryPings.put(c.code+c.fallback,ping);line.setClickable(true);line.setFocusable(true);line.setOnClickListener(v->{if(choosing||!YayVpnService.state.equals("OFF"))return;selected=c;expanded=false;countryPanel.setVisibility(View.GONE);updateStatus();});countryPanel.addView(line);}if(countries.isEmpty())countryPanel.addView(text(t("No countries available. Refresh or contact support.","暂无可用国家，请刷新或联系管理员。","Belum ada negara tersedia. Muat ulang atau hubungi admin."),14,MUTED,false));gap(countryPanel,12);progress=text("",12,MUTED,false);countryPanel.addView(progress);scanButton=button(t("Test country pings","测试国家延迟","Uji ping negara"),false);countryPanel.addView(scanButton);scanButton.setOnClickListener(v->{if(choosing)return;if(probes.isRunning()){probes.cancel();updateStatus();}else scan(false);});countryPanel.addView(text(t("TCP ping · best result in each country. Full VPN access is checked when connecting.","TCP 延迟 · 每个国家的最低值。连接时验证 VPN 网络访问。","Ping TCP · hasil terbaik tiap negara. Akses VPN diuji saat tersambung."),11,MUTED,false));}
    private void updateStatus(){
        if(!screen.equals("home")||power==null)return;
        updateRemaining();updateTelemetry();updateModes();
        String state=YayVpnService.state;
        boolean on=state.equals("ON"),healthy=on&&YayVpnService.internetHealthy,stopping=state.equals("STOPPING"),connecting=choosing||state.equals("CONNECTING");
        power.active=healthy;power.busy=connecting||stopping;
        power.setContentDescription(stopping?t("Disconnecting","正在断开","Memutuskan"):connecting?t("Cancel connection","取消连接","Batalkan koneksi"):on?t("Disconnect","断开","Putuskan"):t("Connect","连接","Hubungkan"));
        power.invalidate();map.connected=healthy;map.invalidate();
        stateTitle.setText(stopping?t("Disconnecting…","正在断开…","Memutuskan…"):connecting?t("Connecting…","正在连接…","Menghubungkan…"):healthy?t("Connected","已连接","Terhubung"):on?t("No internet","无网络","Tidak ada internet"):t("Disconnected","未连接","Terputus"));
        stateTitle.setTextColor(healthy?GREEN:INK);
        stateHint.setText(choosing?t("Testing for up to 12 seconds. Tap to cancel.","最多测试12秒。点击取消。","Menguji hingga 12 detik. Ketuk untuk batal."):stopping?t("Releasing VPN connection…","正在释放 VPN 连接…","Melepas koneksi VPN…"):connecting?t("Checking VPN internet access. Tap to cancel.","正在验证 VPN 网络访问。点击取消。","Memeriksa akses internet VPN. Ketuk untuk batal."):on&&!healthy?t("Checking the tunnel again. Tap to disconnect.","正在重新检查通道。点击断开。","Memeriksa ulang koneksi. Ketuk untuk memutuskan."):healthy?t("Tap to disconnect","点击断开连接","Ketuk untuk memutuskan"):YayVpnService.failed?YayVpnService.message:t("Tap the power button to connect","点击电源按钮连接","Ketuk tombol daya untuk terhubung"));
        long elapsed=on?(SystemClock.elapsedRealtime()-YayVpnService.connectedAt)/1000:0;
        timer.setText(String.format(Locale.ROOT,"%02d:%02d:%02d",elapsed/3600,elapsed%3600/60,elapsed%60));
        String label=selected==null?t("Choose country","选择国家","Pilih negara"):selected.flag()+"  "+selected.label(language);serverLabel.setText(label+"     ⌄");
        for(CountryCatalog.Country c:countries){TextView v=countryPings.get(c.code+c.fallback);if(v!=null){long best=probes.best(c);v.setText(best>=0?best+" ms":probes.tested(c)?t("Unavailable","不可用","Tidak tersedia"):"—");v.setTextColor(best>=0?GREEN:MUTED);}}
        if(scanButton!=null)scanButton.setText(probes.isRunning()?t("Cancel test","取消测试","Batalkan tes"):t("Test country pings","测试国家延迟","Uji ping negara"));
    }
    private void scan(boolean connectAfter){if(selected==null)return;choosing=connectAfter;List<JSONObject> nodes=new ArrayList<>();if(connectAfter)nodes.addAll(selected.nodes);else for(CountryCatalog.Country c:countries)nodes.addAll(c.nodes);probes.scan(nodes,new LatencyProbe.Listener(){public void progress(int done,int total){if(isDestroyed())return;if(progress!=null)progress.setText(done+" / "+total);updateStatus();}public void complete(){if(isDestroyed())return;boolean connect=choosing;choosing=false;updateStatus();if(connect)prepareConnect();}public void unauthorized(){if(!isDestroyed()){choosing=false;sessionEnded();}}},connectAfter?12000:60000);updateStatus();}
    private void toggle(){if(choosing){choosing=false;probes.cancel();updateStatus();return;}if(!YayVpnService.state.equals("OFF")){stopVpn();return;}if(selected==null){expanded=true;countryPanel.setVisibility(View.VISIBLE);return;}if(lightning){probes.cancel();prepareConnect();}else if(probes.best(selected)<0)scan(true);else prepareConnect();}
    private void prepareConnect(){if(selected==null)return;List<String> ids;if(lightning){List<String> choices=new ArrayList<>();for(JSONObject node:selected.nodes)choices.add(node.optString("id"));ids=SelectionPolicy.lightning(choices,new java.security.SecureRandom());}else ids=probes.ranked(selected);if(ids.isEmpty()){toast(t("No reachable server in this country. Test again or choose another country.","此国家暂无可用服务器，请重新测试或选择其他国家。","Tidak ada server terjangkau di negara ini. Uji ulang atau pilih negara lain."));return;}pendingIds=new ArrayList<>(ids.subList(0,Math.min(3,ids.size())));Intent consent=VpnService.prepare(this);if(consent!=null)startActivityForResult(consent,10);else startVpn();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==10){if(result==RESULT_OK)startVpn();else toast(t("VPN permission is needed to connect.","连接需要 VPN 权限。","Izin VPN diperlukan untuk terhubung."));}}
    private void startVpn(){if(selected==null||pendingIds.isEmpty()||!hasSession())return;probes.cancel();try{startForegroundService(new Intent(this,YayVpnService.class).setAction("CONNECT").putStringArrayListExtra("server_ids",pendingIds).putExtra("server_name",selected.label(language)));}catch(Exception e){toast(t("Could not start VPN. Try again.","无法启动 VPN，请重试。","VPN tidak dapat dimulai. Coba lagi."));}}
    private void stopVpn(){if(!YayVpnService.state.equals("OFF"))startService(new Intent(this,YayVpnService.class).setAction("STOP"));}
    private void refresh(){if(loading||choosing||probes.isRunning())return;loading=true;io.execute(()->{try{JSONObject b=api.refresh();runOnUiThread(()->{if(isDestroyed()||loggingOut)return;acceptAccount(b.optJSONObject("account"),true);servers=b.optJSONArray("servers");regroup();if(screen.equals("home")){buildCountries();updateStatus();}});}catch(Exception e){runOnUiThread(()->{if(isDestroyed())return;if(e instanceof Api.Failure&&(((Api.Failure)e).status==401||((Api.Failure)e).status==403))sessionEnded();else toast(errorMessage(e));});}finally{runOnUiThread(()->loading=false);}});}
    private void sessionEnded(){stopVpn();probes.cancel();api.store.clear();account=new JSONObject();servers=new JSONArray();selected=null;welcome();toast(t("Access ended. Sign in again or contact support.","访问已结束，请重新登录或联系管理员。","Akses berakhir. Masuk lagi atau hubungi admin."));}
    private void logout(){if(loggingOut)return;loggingOut=true;choosing=false;probes.cancel();stopVpn();io.execute(()->{try{api.call("POST","/v1/logout",new JSONObject());}catch(Exception ignored){}api.store.clear();runOnUiThread(()->{loggingOut=false;if(isDestroyed())return;account=new JSONObject();servers=new JSONArray();selected=null;probes.close();probes=new LatencyProbe(this,api);welcome();});});}
    private void acceptAccount(JSONObject value,boolean fresh){account=value==null?new JSONObject():value;long now=fresh?account.optLong("server_time",System.currentTimeMillis()/1000):System.currentTimeMillis()/1000;remainingAtSync=Math.max(0,account.optLong("expires_at")-now);accountSyncedAt=SystemClock.elapsedRealtime();updateRemaining();}
    private void updateRemaining(){if(remainingText==null)return;long seconds=Math.max(0,remainingAtSync-(SystemClock.elapsedRealtime()-accountSyncedAt)/1000);remainingText.setText(seconds==0?t("Access expired","访问已过期","Akses kedaluwarsa"):t("Time remaining: ","剩余时间：","Sisa waktu: ")+String.format(Locale.ROOT,t("%dd %02dh %02dm %02ds","%d天 %02d时 %02d分 %02d秒","%dh %02dj %02dm %02dd"),seconds/86400,seconds%86400/3600,seconds%3600/60,seconds%60));}
    private void devices(){
        if(deviceBusy||!hasSession())return;deviceBusy=true;base("devices");header(true,"YAY VPN");root.addView(text(t("Loading devices…","正在加载设备…","Memuat perangkat…"),16,MUTED,false));final int page=devicePage;
        io.execute(()->{try{JSONObject result=api.call("GET","/v1/devices",null);runOnUiThread(()->{if(!isDestroyed()&&page==devicePage&&!loggingOut)renderDevices(result);});}
        catch(Exception error){runOnUiThread(()->{if(isDestroyed()||page!=devicePage)return;if(error instanceof Api.Failure&&(((Api.Failure)error).status==401||((Api.Failure)error).status==403)){sessionEnded();return;}root.addView(text(errorMessage(error),14,RED,false));action(root,t("Retry","重试","Coba lagi"),false,()->devices());});}
        finally{runOnUiThread(()->deviceBusy=false);}});
    }
    private void renderDevices(JSONObject result){
        acceptAccount(result.optJSONObject("account"),true);base("devices");header(true,"YAY VPN");JSONArray list=result.optJSONArray("devices");if(list==null)list=new JSONArray();int limit=account.optInt("device_limit");
        root.addView(text(t("Devices & limit","设备与上限","Perangkat & batas"),25,INK,true));gap(root,12);root.addView(text(list.length()+" / "+limit+" "+t("slots used","个设备位已使用","slot digunakan"),16,MUTED,false));gap(root,18);
        for(int i=0;i<list.length();i++){
            JSONObject item=list.optJSONObject(i);if(item==null)continue;String id=item.optString("id"),name=item.optString("name");boolean current=item.optBoolean("is_current");
            LinearLayout line=row();line.setPadding(dp(14),dp(12),dp(12),dp(12));line.setBackground(shape(CARD,14));
            String details=id.substring(0,Math.min(8,id.length()))+" · "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,Locale.forLanguageTag(language)).format(new Date(item.optLong("last_seen")*1000));
            TextView label=text(name+"\n"+details+(current?"\n"+t("This device","当前设备","Perangkat ini"):""),16,INK,true);line.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            if(!current){Button remove=button(t("Remove","移除","Hapus"),false);line.addView(remove,new LinearLayout.LayoutParams(-2,dp(52)));remove.setOnClickListener(v->confirmRemoval(id,name,remove));}
            root.addView(line,new LinearLayout.LayoutParams(-1,-2));gap(root,12);
        }
        for(int i=list.length();i<limit;i++){
            Button add=button("+",false);add.setTextSize(44);add.setContentDescription(t("Add another device","添加另一台设备","Tambahkan perangkat lain"));root.addView(add,new LinearLayout.LayoutParams(-1,dp(96)));
            add.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(t("Add a device","添加设备","Tambah perangkat")).setMessage(t("Install Yay VPN on your other device and sign in with this same account. An empty slot fills automatically.","在另一台设备上安装 Yay VPN 并使用同一账号登录，空位将自动填充。","Pasang Yay VPN di perangkat lain dan masuk dengan akun yang sama. Slot kosong terisi otomatis.")).setPositiveButton("OK",null).show());
            centered(root,text(t("Empty device slot","空设备位","Slot perangkat kosong"),13,MUTED,false));gap(root,12);
        }
        action(root,t("Refresh devices","刷新设备","Muat ulang perangkat"),false,()->devices());
    }
    private void confirmRemoval(String id,String name,Button button){
        if(deviceBusy)return;
        new AlertDialog.Builder(this).setTitle(t("Remove device?","移除设备？","Hapus perangkat?"))
          .setMessage(name+"\n\n"+t("This signs out the other device. If someone knows your password, ask the administrator to change it.","这将使另一台设备退出登录。若有人知道你的密码，请联系管理员修改。","Perangkat lain akan keluar. Jika orang lain tahu kata sandi Anda, minta admin menggantinya."))
          .setNegativeButton(t("Cancel","取消","Batal"),null).setPositiveButton(t("Remove","移除","Hapus"),(dialog,which)->{
            if(deviceBusy)return;deviceBusy=true;button.setEnabled(false);final int page=devicePage;
            io.execute(()->{try{JSONObject result=api.call("DELETE","/v1/devices/"+Uri.encode(id),null);runOnUiThread(()->{if(!isDestroyed()&&page==devicePage&&!loggingOut)renderDevices(result);});}
            catch(Exception error){runOnUiThread(()->{if(isDestroyed()||page!=devicePage)return;if(error instanceof Api.Failure&&(((Api.Failure)error).status==401||((Api.Failure)error).status==403))sessionEnded();else toast(t("Removal failed. Refresh devices and try again.","移除失败，请刷新设备后重试。","Gagal menghapus. Muat ulang perangkat dan coba lagi."));});}
            finally{runOnUiThread(()->{deviceBusy=false;button.setEnabled(true);});}});
          }).show();
    }
    private String expiry(){return DateFormat.getDateInstance(DateFormat.MEDIUM,Locale.forLanguageTag(language)).format(new Date(account.optLong("expires_at")*1000));}
    private String errorMessage(Exception e){
        if(e instanceof Api.Failure){
            int status=((Api.Failure)e).status;
            if(status==409)return t("Device limit reached. Contact support.","已达到设备上限，请联系管理员。","Batas perangkat tercapai. Hubungi admin.");
            if(status==401)return t("Incorrect credentials or expired session.","账号密码错误或登录已过期。","Akun salah atau sesi kedaluwarsa.");
            if(status==403)return t("Access expired or paused. Contact support.","访问已过期或暂停，请联系管理员。","Akses kedaluwarsa atau dijeda. Hubungi admin.");
            if(status==429)return t("Too many attempts. Wait five minutes.","尝试过多，请等待五分钟。","Terlalu banyak percobaan. Tunggu lima menit.");
            return t("The login service returned an error. Contact support. HTTP ","登录服务返回错误，请联系管理员。HTTP ","Layanan masuk mengembalikan kesalahan. Hubungi admin. HTTP ")+status;
        }
        if(e instanceof Api.NoNetwork)return t("No active internet connection. Enable Wi-Fi or mobile data.","没有可用网络，请开启 Wi-Fi 或移动数据。","Tidak ada koneksi internet aktif. Aktifkan Wi-Fi atau data seluler.");
        if(e instanceof java.net.UnknownHostException)return t("Cannot find the login server. Try another network.","无法解析登录服务器地址，请尝试其他网络。","Alamat server masuk tidak dapat ditemukan. Coba jaringan lain.");
        if(e instanceof java.net.SocketTimeoutException)return t("The login server took too long to respond. Try another network.","登录服务器响应超时，请尝试其他网络。","Server masuk terlalu lama merespons. Coba jaringan lain.");
        if(e instanceof javax.net.ssl.SSLException)return t("Secure connection failed. Check automatic date/time or try another network.","安全连接失败，请检查自动日期和时间，或尝试其他网络。","Koneksi aman gagal. Periksa tanggal dan waktu otomatis atau coba jaringan lain.");
        if(e instanceof java.net.SocketException)return t("Cannot connect to the login server on this network. Try Wi-Fi or mobile data.","当前网络无法连接登录服务器，请切换 Wi-Fi 或移动数据。","Tidak dapat terhubung ke server masuk di jaringan ini. Coba Wi-Fi atau data seluler.");
        if(e instanceof Api.UnexpectedResponse)return t("Unexpected response from the login service. Contact support.","登录服务响应异常，请联系管理员。","Respons layanan masuk tidak sesuai. Hubungi admin.");
        return t("Could not complete sign-in. Try again or contact support.","无法完成登录，请重试或联系管理员。","Tidak dapat menyelesaikan proses masuk. Coba lagi atau hubungi admin.");
    }
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){if(!screen.equals("home")&&!screen.equals("welcome")){if(hasSession())home();else welcome();}else if(screen.equals("home")&&expanded){expanded=false;countryPanel.setVisibility(View.GONE);}else super.onBackPressed();}
    private final class PowerButton extends View {
        boolean active,busy;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        PowerButton(){super(MainActivity.this);setClickable(true);setFocusable(true);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        @Override protected void onDraw(Canvas c){float x=getWidth()/2f,y=getHeight()/2f,r=getWidth()*.36f;int color=active?GREEN:RED;p.setStyle(Paint.Style.FILL);p.setColor(0xff07090c);p.setShadowLayer(dp(24),0,0,active?0x404ce5a0:0x40f34b59);c.drawCircle(x,y,r,p);p.clearShadowLayer();p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(color);c.drawCircle(x,y,r,p);p.setColor(active?0x554ce5a0:0x55f34b59);p.setStrokeWidth(dp(1));c.drawCircle(x,y,r+dp(10),p);p.setColor(color);p.setStrokeWidth(dp(5));p.setStrokeCap(Paint.Cap.ROUND);float k=dp(24);c.drawArc(new RectF(x-k,y-k+dp(4),x+k,y+k+dp(4)),310,280,false,p);c.drawLine(x,y-dp(26),x,y-dp(1),p);if(busy){p.setStrokeWidth(dp(3));float a=(SystemClock.elapsedRealtime()%1500)*360f/1500;c.drawArc(new RectF(x-r-dp(10),y-r-dp(10),x+r+dp(10),y+r+dp(10)),a,90,false,p);postInvalidateDelayed(25);}}
    }
}
