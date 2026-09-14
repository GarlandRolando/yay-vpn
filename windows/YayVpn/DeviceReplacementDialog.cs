using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace YayVpn;

static class DeviceReplacementDialog {
    internal static string? Choose(JsonArray devices){
        string? selected=null;
        void Show(){
            string lang="en";try{var file=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"YayVPN","language.txt");if(File.Exists(file))lang=File.ReadAllText(file);}catch{}
            string T(string en,string zh,string id)=>lang=="zh"?zh:lang=="id"?id:en;
            var window=new Window{
                Title=T("Device limit reached","已达到设备上限","Batas perangkat tercapai"),Width=520,Height=430,
                WindowStartupLocation=WindowStartupLocation.CenterOwner,ResizeMode=ResizeMode.NoResize,ShowInTaskbar=false,
                Background=new SolidColorBrush(Color.FromRgb(8,9,11)),Foreground=Brushes.White
            };
            if(Application.Current?.MainWindow is Window owner&&owner.IsVisible)window.Owner=owner;
            var root=new Grid{Margin=new Thickness(24)};root.RowDefinitions.Add(new RowDefinition{Height=GridLength.Auto});root.RowDefinitions.Add(new RowDefinition{Height=GridLength.Auto});root.RowDefinitions.Add(new RowDefinition{Height=new GridLength(1,GridUnitType.Star)});root.RowDefinitions.Add(new RowDefinition{Height=GridLength.Auto});
            var title=new TextBlock{Text=T("Choose a device to replace","选择要替换的设备","Pilih perangkat yang akan diganti"),FontSize=24,FontWeight=FontWeights.Bold,Margin=new Thickness(0,0,0,8)};root.Children.Add(title);
            var help=new TextBlock{Text=T("The selected device will be signed out immediately. This computer will then use that device slot.","所选设备会立即退出登录，然后这台电脑将使用该设备位。","Perangkat yang dipilih akan langsung dikeluarkan. Komputer ini kemudian memakai slot tersebut."),TextWrapping=TextWrapping.Wrap,Foreground=Brushes.Gray,Margin=new Thickness(0,0,0,16)};Grid.SetRow(help,1);root.Children.Add(help);
            var list=new ListBox{Background=new SolidColorBrush(Color.FromRgb(17,19,23)),Foreground=Brushes.White,BorderThickness=new Thickness(0),Padding=new Thickness(8)};
            foreach(var node in devices.OfType<JsonObject>()){
                string id=node["id"]?.ToString()??"";if(id.Length==0)continue;string name=node["name"]?.ToString()??T("Unnamed device","未命名设备","Perangkat tanpa nama");
                string seen="";if(long.TryParse(node["last_seen"]?.ToString(),out var unix)&&unix>0)seen="\n"+T("Last used: ","最近使用：","Terakhir digunakan: ")+DateTimeOffset.FromUnixTimeSeconds(unix).LocalDateTime.ToString("g");
                list.Items.Add(new ListBoxItem{Tag=id,Content=new TextBlock{Text=name+seen,TextWrapping=TextWrapping.Wrap,Margin=new Thickness(8),Foreground=Brushes.White}});
            }
            Grid.SetRow(list,2);root.Children.Add(list);
            var buttons=new Grid{Margin=new Thickness(0,16,0,0)};buttons.ColumnDefinitions.Add(new ColumnDefinition());buttons.ColumnDefinitions.Add(new ColumnDefinition());
            var cancel=new Button{Content=T("Cancel","取消","Batal"),Height=44,Margin=new Thickness(0,0,6,0)};cancel.Click+=(_,_)=>{window.DialogResult=false;window.Close();};buttons.Children.Add(cancel);
            var replace=new Button{Content=T("Replace device","替换设备","Ganti perangkat"),Height=44,Margin=new Thickness(6,0,0,0),IsDefault=true};replace.Click+=(_,_)=>{if(list.SelectedItem is not ListBoxItem item||item.Tag is not string id||id.Length==0)return;selected=id;window.DialogResult=true;window.Close();};Grid.SetColumn(replace,1);buttons.Children.Add(replace);Grid.SetRow(buttons,3);root.Children.Add(buttons);
            window.Content=root;window.ShowDialog();
        }
        if(Application.Current?.Dispatcher is { } dispatcher&&!dispatcher.CheckAccess())dispatcher.Invoke(Show);else Show();
        return selected;
    }
}
