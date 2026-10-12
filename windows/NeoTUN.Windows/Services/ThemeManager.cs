using System.Windows;
using System.Windows.Media;
using Color = System.Windows.Media.Color;
using ColorConverter = System.Windows.Media.ColorConverter;

namespace NeoTUN.Windows.Services;

public static class ThemeManager
{
    public static void ApplyTheme(string theme)
    {
        var app = Application.Current;
        if (app == null) return;

        var normalized = theme.ToLowerInvariant() switch
        {
            "light" => "light",
            "oled" => "oled",
            _ => "dark"
        };

        var (bg, nav, surface, raised, input, border, borderAccent, brandViolet, brandVioletLight, brandBlue, brandSoft, navSelected, textPrimary, textSecondary, textMuted, success, successSurface, danger, dangerSurface, warning) = normalized switch
        {
            "light" => (
                "#F4F5FA", "#FFFFFF", "#FFFFFF", "#E9EBF5", "#F0F1F8",
                "#D9DCEC", "#C4B5FD", "#7C3AED", "#6D28D9", "#7657E8",
                "#EDE5FF", "#EDE5FF", "#191A28", "#50566B", "#747B91",
                "#147B55", "#DDF6EA", "#AD3151", "#FFE8EE", "#B45309"
            ),
            "oled" => (
                "#000000", "#050509", "#09090F", "#11111B", "#05050A",
                "#242435", "#6941C6", "#8B5CF6", "#C4B5FD", "#6D8DFF",
                "#170D29", "#170D29", "#F7F8FF", "#B1B8CD", "#858EA8",
                "#65E7B0", "#09251D", "#FFB5C3", "#26101A", "#FFCA7A"
            ),
            _ => (
                "#0B0815", "#120D20", "#191226", "#251A36", "#120C1E",
                "#38284F", "#7851B8", "#8B5CF6", "#C4B5FD", "#6D8DFF",
                "#2A1B45", "#2A1B45", "#F7F8FF", "#B1B8CD", "#858EA8",
                "#65E7B0", "#12362F", "#FFB5C3", "#321C29", "#FFCA7A"
            )
        };

        SetResourceColor(app, "BackgroundColor", bg);
        SetResourceColor(app, "NavigationColor", nav);
        SetResourceColor(app, "SurfaceColor", surface);
        SetResourceColor(app, "RaisedSurfaceColor", raised);
        SetResourceColor(app, "InputSurfaceColor", input);
        SetResourceColor(app, "BorderColor", border);
        SetResourceColor(app, "BorderAccentColor", borderAccent);
        SetResourceColor(app, "BrandVioletColor", brandViolet);
        SetResourceColor(app, "BrandVioletLightColor", brandVioletLight);
        SetResourceColor(app, "BrandBlueColor", brandBlue);
        SetResourceColor(app, "BrandSoftColor", brandSoft);
        SetResourceColor(app, "NavSelectedColor", navSelected);
        SetResourceColor(app, "TextPrimaryColor", textPrimary);
        SetResourceColor(app, "TextSecondaryColor", textSecondary);
        SetResourceColor(app, "TextMutedColor", textMuted);
        SetResourceColor(app, "SuccessColor", success);
        SetResourceColor(app, "SuccessSurfaceColor", successSurface);
        SetResourceColor(app, "DangerColor", danger);
        SetResourceColor(app, "DangerSurfaceColor", dangerSurface);
        SetResourceColor(app, "WarningColor", warning);

        SetResourceBrush(app, "BackgroundBrush", bg);
        SetResourceBrush(app, "NavigationBrush", nav);
        SetResourceBrush(app, "SurfaceBrush", surface);
        SetResourceBrush(app, "RaisedSurfaceBrush", raised);
        SetResourceBrush(app, "InputSurfaceBrush", input);
        SetResourceBrush(app, "BorderBrushNeo", border);
        SetResourceBrush(app, "BorderAccentBrush", borderAccent);
        SetResourceBrush(app, "BrandVioletBrush", brandViolet);
        SetResourceBrush(app, "BrandVioletLightBrush", brandVioletLight);
        SetResourceBrush(app, "BrandBlueBrush", brandBlue);
        SetResourceBrush(app, "BrandSoftBrush", brandSoft);
        SetResourceBrush(app, "NavSelectedBrush", navSelected);
        SetResourceBrush(app, "TextPrimaryBrush", textPrimary);
        SetResourceBrush(app, "TextSecondaryBrush", textSecondary);
        SetResourceBrush(app, "TextMutedBrush", textMuted);
        SetResourceBrush(app, "SuccessBrush", success);
        SetResourceBrush(app, "SuccessSurfaceBrush", successSurface);
        SetResourceBrush(app, "DangerBrush", danger);
        SetResourceBrush(app, "DangerSurfaceBrush", dangerSurface);
        SetResourceBrush(app, "WarningBrush", warning);
    }

    private static void SetResourceColor(Application app, string key, string hex)
    {
        var color = (System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString(hex);
        app.Resources[key] = color;
    }

    private static void SetResourceBrush(Application app, string key, string hex)
    {
        var color = (Color)ColorConverter.ConvertFromString(hex);
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        app.Resources[key] = brush;
    }
}
