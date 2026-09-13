import SwiftUI
#if os(iOS)
import UIKit
#else
import AppKit
#endif

@main struct YayApp: App {
    var body: some Scene { WindowGroup { ContentView().preferredColorScheme(.dark) } }
}
struct ContentView: View {
    @StateObject private var model = YayModel()
    @AppStorage("language") private var language = "en"
    @State private var removal: YayDevice?
    @State private var addingDevice = false
    @State private var username = ""
    @State private var password = ""
    @State private var page = "welcome"
    @State private var expanded = false
    func t(_ en: String, _ zh: String, _ id: String) -> String { language == "zh" ? zh : language == "id" ? id : en }
    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                HStack {
                    Button { if page == "settings" || page == "contact" || page == "login" || page == "devices" { page = "welcome" } else { page = "settings" } } label: { Image(systemName: page == "settings" || page == "contact" || page == "login" || page == "devices" ? "arrow.left" : "gearshape") }.frame(width: 44, height: 44)
                    Spacer(); Image("YayLogo").resizable().frame(width: 30, height: 30).accessibilityHidden(true); Text("YAY VPN").font(.title2.bold()); Spacer(); Color.clear.frame(width: 44, height: 44)
                }
                if page == "contact" {
                    Text(t("Get your Yay account.", "获取你的 Yay 账号。", "Dapatkan akun Yay.")).font(.title.bold())
                    Text(t("Contact us for a login ID, password, device limit and access period.", "联系我们获取账号密码、设备数量和有效期。", "Hubungi kami untuk akun, batas perangkat, dan masa akses."))
                    Button("Weixin: wxid_3lt1aad36ai822") {
                        #if os(iOS)
                        UIPasteboard.general.string = "wxid_3lt1aad36ai822"
                        #else
                        NSPasteboard.general.clearContents(); NSPasteboard.general.setString("wxid_3lt1aad36ai822", forType: .string)
                        #endif
                        model.status = t("Weixin ID copied", "微信号已复制", "ID Weixin disalin")
                    }
                    Link("WhatsApp: +371 28 635 209", destination: URL(string: "https://wa.me/37128635209")!)
                    Link("Email: dummystorage22@gmail.com", destination: URL(string: "mailto:dummystorage22@gmail.com")!)
                } else if page == "devices", model.signedIn {
                    deviceSettings
                } else if page == "settings" {
                    Button(t("Help", "帮助", "Bantuan")) { page = "contact" }
                    Picker(t("Language", "语言", "Bahasa"), selection: $language) { Text("English").tag("en"); Text("中文").tag("zh"); Text("Bahasa Indonesia").tag("id") }
                    if model.signedIn { Button(t("Devices & limit", "设备与上限", "Perangkat & batas")) { page = "devices" }.disabled(model.busy || model.deviceBusy) }
                    if model.signedIn { Button(t("Log out", "退出登录", "Keluar")) { Task { await model.logout(); page = "welcome" } }.disabled(model.busy) }
                } else if model.signedIn {
                    Picker(t("Country", "国家", "Negara"), selection: $model.country) { ForEach(model.countries, id: \.self) { Text(flag($0) + " " + localizedCountry($0)).tag($0) } }.disabled(model.busy || model.connected)
                    DisclosureGroup(t("Country pings", "国家延迟", "Ping negara"), isExpanded: $expanded) {
                        ForEach(model.countries, id: \.self) { country in HStack { Text(flag(country) + " " + localizedCountry(country)); Spacer(); Text(model.pings[country].map { $0 >= 0 ? "\($0) ms" : "—" } ?? "—").foregroundStyle(.secondary) } }
                        Button(t("Test country pings", "测试国家延迟", "Uji ping negara")) { model.measureAll() }.disabled(model.busy || model.connected)
                        Text(t("TCP ping · full VPN access is checked when connecting.", "TCP 延迟 · 连接时验证 VPN 网络访问。", "Ping TCP · akses VPN diuji saat tersambung.")).font(.caption).foregroundStyle(.secondary)
                    }
                    ZStack { WorldMap(active: model.connected); Button { model.toggle() } label: { Image(systemName: "power").font(.system(size: 60)).frame(width: 170, height: 170).background(.black).clipShape(Circle()).overlay(Circle().stroke(model.connected ? Color.green : Color.red, lineWidth: 2)) }.accessibilityLabel(t(model.connected ? "Disconnect" : "Connect", model.connected ? "断开" : "连接", model.connected ? "Putuskan" : "Hubungkan")) }.frame(height: 290)
                    Text(t(model.connected ? "Connected" : model.busy ? "Connecting…" : "Disconnected", model.connected ? "已连接" : model.busy ? "正在连接…" : "未连接", model.connected ? "Terhubung" : model.busy ? "Menghubungkan…" : "Terputus")).font(.title.bold())
                    TimelineView(.periodic(from: .now, by: 1)) { _ in Text(remainingAccess).font(.callout.monospacedDigit()) }
                    Button(t("Refresh locations", "刷新地区", "Muat ulang lokasi")) { Task { await model.refresh() } }.disabled(model.busy || model.connected)
                } else if page == "login" {
                    Text(t("Welcome back.", "欢迎回来。", "Selamat datang kembali.")).font(.largeTitle.bold())
                    TextField(t("Login ID", "账号", "ID masuk"), text: $username)
                    SecureField(t("Password", "密码", "Kata sandi"), text: $password)
                    Button(t("Log in", "登录", "Masuk")) { Task { await model.login(username.trimmingCharacters(in: .whitespacesAndNewlines), password); password = ""; if model.signedIn { page = "welcome" } } }.disabled(model.busy)
                } else {
                    ZStack { WorldMap(active: false); Text("YAY").font(.system(size: 70, weight: .bold)) }.frame(height: 240)
                    Text(t("Your world. Connected.", "连接你的世界。", "Duniamu. Terhubung.")).font(.largeTitle.bold())
                    Button(t("Log in", "登录", "Masuk")) { page = "login" }
                    Button(t("Sign up", "注册", "Daftar")) { page = "contact" }
                }
                Text(model.status).font(.footnote).foregroundStyle(.secondary)
            }.padding(28).frame(maxWidth: 540)
        }.frame(maxWidth: .infinity, maxHeight: .infinity).background(.black).tint(model.connected ? .green : .red).buttonStyle(.bordered).task { if model.signedIn { await model.refresh() } }
    }
    var remainingAccess: String {
        let seconds = model.remainingSeconds
        if seconds == 0 { return t("Access expired", "访问已过期", "Akses kedaluwarsa") }
        return t("Time remaining: ", "剩余时间：", "Sisa waktu: ") + String(format: t("%lldd %02lldh %02lldm %02llds", "%lld天 %02lld时 %02lld分 %02lld秒", "%lldh %02lldj %02lldm %02lldd"), seconds/86400, seconds%86400/3600, seconds%3600/60, seconds%60)
    }
    var deviceSettings: some View {
        VStack(spacing: 14) {
            Text(t("Devices & limit", "设备与上限", "Perangkat & batas")).font(.title.bold())
            Text("\(model.devices.count) / \(model.deviceLimit) " + t("slots used", "个设备位已使用", "slot digunakan"))
            if model.deviceBusy { ProgressView() }
            ForEach(model.devices) { device in
                HStack {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(device.name).font(.headline)
                        Text(String(device.id.prefix(8)) + " · " + device.lastSeen.formatted(date: .numeric, time: .shortened)).font(.caption).foregroundStyle(.secondary)
                        if device.isCurrent { Text(t("This device", "当前设备", "Perangkat ini")).font(.caption).foregroundStyle(.secondary) }
                    }
                    Spacer()
                    if !device.isCurrent { Button(t("Remove", "移除", "Hapus")) { removal = device }.disabled(model.deviceBusy) }
                }.padding(16).background(Color(white: 0.07)).clipShape(RoundedRectangle(cornerRadius: 14))
            }
            ForEach(0..<model.freeSlots, id: \.self) { _ in
                Button { addingDevice = true } label: {
                    VStack { Text("+").font(.system(size: 46, weight: .light)); Text(t("Empty device slot", "空设备位", "Slot perangkat kosong")).font(.caption) }.frame(maxWidth: .infinity, minHeight: 94)
                }.accessibilityLabel(t("Add another device", "添加另一台设备", "Tambahkan perangkat lain"))
            }
            Button(t("Refresh devices", "刷新设备", "Muat ulang perangkat")) { Task { await model.loadDevices() } }.disabled(model.deviceBusy)
        }.task { await model.loadDevices() }
        .alert(t("Remove device?", "移除设备？", "Hapus perangkat?"), isPresented: Binding(get: { removal != nil }, set: { if !$0 { removal = nil } }), presenting: removal) { device in
            Button(t("Remove", "移除", "Hapus"), role: .destructive) { Task { await model.removeDevice(device) }; removal = nil }
            Button(t("Cancel", "取消", "Batal"), role: .cancel) { removal = nil }
        } message: { device in
            Text(device.name + "\n" + t("This signs out the other device. If someone knows your password, ask the administrator to change it.", "这将使另一台设备退出登录。若有人知道你的密码，请联系管理员修改。", "Perangkat lain akan keluar. Jika orang lain tahu kata sandi Anda, minta admin menggantinya."))
        }
        .alert(t("Add a device", "添加设备", "Tambah perangkat"), isPresented: $addingDevice) { Button("OK", role: .cancel) {} } message: {
            Text(t("Install Yay VPN on your other device and sign in with the same account. It will fill an empty slot automatically.", "在另一台设备上安装 Yay VPN 并使用同一账号登录，空位将自动填充。", "Pasang Yay VPN di perangkat lain dan masuk dengan akun yang sama. Slot kosong terisi otomatis."))
        }
    }
    func code(_ country: String) -> String { ["Singapore":"SG", "Hong Kong":"HK", "Japan":"JP", "India":"IN", "South Africa":"ZA", "South Korea":"KR", "United States":"US", "United Kingdom":"GB", "Australia":"AU", "Germany":"DE", "Spain":"ES", "Brazil":"BR"][country] ?? "" }
    func flag(_ country: String) -> String { code(country).unicodeScalars.compactMap { UnicodeScalar(127397 + $0.value) }.map(String.init).joined() }
    func localizedCountry(_ country: String) -> String { Locale(identifier: language).localizedString(forRegionCode: code(country)) ?? country }
}
