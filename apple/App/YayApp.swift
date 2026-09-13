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
    @State private var username = "", password = "", page = "welcome", expanded = false
    func t(_ en: String, _ zh: String, _ id: String) -> String { language == "zh" ? zh : language == "id" ? id : en }
    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                HStack {
                    Button { if page == "settings" || page == "contact" || page == "login" { page = "welcome" } else { page = "settings" } } label: { Image(systemName: page == "settings" || page == "contact" || page == "login" ? "arrow.left" : "gearshape") }.frame(width: 44, height: 44)
                    Spacer(); Text("YAY VPN").font(.title2.bold()); Spacer(); Color.clear.frame(width: 44, height: 44)
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
                } else if page == "settings" {
                    Button(t("Help", "帮助", "Bantuan")) { page = "contact" }
                    Picker(t("Language", "语言", "Bahasa"), selection: $language) { Text("English").tag("en"); Text("中文").tag("zh"); Text("Bahasa Indonesia").tag("id") }
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
    func code(_ country: String) -> String { ["Singapore":"SG", "Hong Kong":"HK", "Japan":"JP", "India":"IN", "South Africa":"ZA", "South Korea":"KR", "United States":"US", "United Kingdom":"GB", "Australia":"AU", "Germany":"DE", "Spain":"ES", "Brazil":"BR"][country] ?? "" }
    func flag(_ country: String) -> String { code(country).unicodeScalars.compactMap { UnicodeScalar(127397 + $0.value) }.map(String.init).joined() }
    func localizedCountry(_ country: String) -> String { Locale(identifier: language).localizedString(forRegionCode: code(country)) ?? country }
}
