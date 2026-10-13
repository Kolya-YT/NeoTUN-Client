using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace NeoTUN.Windows.Models;

public sealed class RoutingProfile : INotifyPropertyChanged
{
    private string _name = "Маршрутизация";
    private bool _globalProxy = true;
    private int _routeOrder = 0; // 0 = block-proxy-direct, 1 = block-direct-proxy, 2 = proxy-block-direct
    private string _remoteDns = "https://8.8.8.8/dns-query";
    private string _domesticDns = "https://77.88.8.8/dns-query";
    private string _domesticDnsDomains = "ru\nsu\nрф";
    private string _blockSites = "";
    private string _blockIp = "";
    private string _proxySites = "";
    private string _proxyIp = "";
    private string _directSites = "";
    private string _directIp = "";
    private string _processExclusions = "";
    private bool _ipv6Enabled = false;
    private int _mtu = 1500;

    public string Id { get; set; } = Guid.NewGuid().ToString();

    public string Name
    {
        get => _name;
        set { if (_name != value) { _name = value; OnPropertyChanged(); } }
    }

    public string? SourceUrl { get; set; }

    // Preserves arbitrary Happ/INCY ordering while RouteOrder remains the legacy UI preset.
    public string? RouteOrderRaw { get; set; }

    public DateTimeOffset? UpdatedAt { get; set; }

    public bool GlobalProxy
    {
        get => _globalProxy;
        set { if (_globalProxy != value) { _globalProxy = value; OnPropertyChanged(); } }
    }

    public int RouteOrder
    {
        get => _routeOrder;
        set { if (_routeOrder != value) { _routeOrder = value; RouteOrderRaw = null; OnPropertyChanged(); OnPropertyChanged(nameof(RouteOrderLabel)); } }
    }

    public string RouteOrderLabel => RouteOrder switch
    {
        1 => "Block → Direct → Proxy",
        2 => "Proxy → Block → Direct",
        _ => "Block → Proxy → Direct"
    };

    public string RemoteDns
    {
        get => _remoteDns;
        set { if (_remoteDns != value) { _remoteDns = value; OnPropertyChanged(); } }
    }

    public string DomesticDns
    {
        get => _domesticDns;
        set { if (_domesticDns != value) { _domesticDns = value; OnPropertyChanged(); } }
    }

    public string DomesticDnsDomains
    {
        get => _domesticDnsDomains;
        set { if (_domesticDnsDomains != value) { _domesticDnsDomains = value; OnPropertyChanged(); OnPropertyChanged(nameof(DomesticDomainsCount)); } }
    }

    public string BlockSites
    {
        get => _blockSites;
        set { if (_blockSites != value) { _blockSites = value; OnPropertyChanged(); OnPropertyChanged(nameof(BlockSitesCount)); } }
    }

    public string BlockIp
    {
        get => _blockIp;
        set { if (_blockIp != value) { _blockIp = value; OnPropertyChanged(); OnPropertyChanged(nameof(BlockIpCount)); } }
    }

    public string ProxySites
    {
        get => _proxySites;
        set { if (_proxySites != value) { _proxySites = value; OnPropertyChanged(); OnPropertyChanged(nameof(ProxySitesCount)); } }
    }

    public string ProxyIp
    {
        get => _proxyIp;
        set { if (_proxyIp != value) { _proxyIp = value; OnPropertyChanged(); OnPropertyChanged(nameof(ProxyIpCount)); } }
    }

    public string DirectSites
    {
        get => _directSites;
        set { if (_directSites != value) { _directSites = value; OnPropertyChanged(); OnPropertyChanged(nameof(DirectSitesCount)); } }
    }

    public string DirectIp
    {
        get => _directIp;
        set { if (_directIp != value) { _directIp = value; OnPropertyChanged(); OnPropertyChanged(nameof(DirectIpCount)); } }
    }

    public string ProcessExclusions
    {
        get => _processExclusions;
        set { if (_processExclusions != value) { _processExclusions = value; OnPropertyChanged(); OnPropertyChanged(nameof(ProcessExclusionsCount)); } }
    }

    public bool Ipv6Enabled
    {
        get => _ipv6Enabled;
        set { if (_ipv6Enabled != value) { _ipv6Enabled = value; OnPropertyChanged(); } }
    }

    public int Mtu
    {
        get => _mtu;
        set { if (_mtu != value) { _mtu = value; OnPropertyChanged(); } }
    }

    public int BlockSitesCount => CountItems(BlockSites);
    public int BlockIpCount => CountItems(BlockIp);
    public int ProxySitesCount => CountItems(ProxySites);
    public int ProxyIpCount => CountItems(ProxyIp);
    public int DirectSitesCount => CountItems(DirectSites);
    public int DirectIpCount => CountItems(DirectIp);
    public int ProcessExclusionsCount => CountItems(ProcessExclusions);
    public int DomesticDomainsCount => CountItems(DomesticDnsDomains);

    private static int CountItems(string text) =>
        string.IsNullOrWhiteSpace(text) ? 0 : text.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Count(l => !l.StartsWith("#"));

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
