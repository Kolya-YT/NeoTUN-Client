#define AppName "NeoTUN"
#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif
#define AppPublisher "NeoTUN"
#define AppExeName "NeoTUN.exe"

[Setup]
AppId={{2F7C1D9A-6B2E-4C41-9F53-8A2D4E7B106C}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\NeoTUN
DefaultGroupName=NeoTUN
DisableProgramGroupPage=yes
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
PrivilegesRequired=admin
OutputDir=..\dist
OutputBaseFilename=NeoTUN-Setup-x64
UninstallDisplayIcon={app}\{#AppExeName}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
Uninstallable=yes
ChangesEnvironment=no
VersionInfoVersion={#AppVersion}.0
VersionInfoProductName=NeoTUN
VersionInfoDescription=NeoTUN Windows Desktop Installer

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; GroupDescription: "Дополнительные ярлыки:"; Flags: unchecked

[Files]
Source: "..\dist\NeoTUN-Windows\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\NeoTUN"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"
Name: "{autodesktop}\NeoTUN"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "Запустить NeoTUN"; Flags: postinstall nowait skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}\runtime"
