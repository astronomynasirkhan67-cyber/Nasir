#define MyAppName "NK Voice AI"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "AstronomyNasir"
#define MyAppExeName "NKVoiceAI.exe"

[Setup]
AppId={{C3A3B3C8-9DBB-4D54-A97A-70D5D902A0C1}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\NK Voice AI
DefaultGroupName=NK Voice AI
OutputDir=dist
OutputBaseFilename=NKVoiceAI-Setup
Compression=lzma
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64
PrivilegesRequired=lowest

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"

[Files]
Source: "dist\NKVoiceAI\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\NK Voice AI"; Filename: "{app}\NKVoiceAI.exe"
Name: "{autodesktop}\NK Voice AI"; Filename: "{app}\NKVoiceAI.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\NKVoiceAI.exe"; Description: "Launch NK Voice AI"; Flags: postinstall nowait skipifsilent
