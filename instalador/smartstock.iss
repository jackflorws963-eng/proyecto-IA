; SmartStock IoT - instalador para Windows (Inno Setup 6).  Autor: Brayan (BR)
; No se compila a mano: lo llama empaquetar-escritorio.ps1, que define Version, Origen y Salida.

#ifndef Version
  #define Version "2.1.0"
#endif
#ifndef Origen
  #define Origen "..\dist\SmartStock IoT"
#endif
#ifndef Salida
  #define Salida "..\dist"
#endif

#define Nombre "SmartStock IoT"
#define Ejecutable "SmartStock IoT.exe"

[Setup]
AppId={{B7E2D9A4-5C31-4E8F-9A6B-2F0D8C1E7B53}
AppName={#Nombre}
AppVersion={#Version}
AppVerName={#Nombre} {#Version}
AppPublisher=Brayan (BR)
AppCopyright=Copyright (c) 2026 Brayan (BR)
AppComments=Sistema Inteligente de Inventario mediante Internet de las Cosas
VersionInfoVersion={#Version}
VersionInfoCompany=Brayan (BR)
VersionInfoDescription=Instalador de {#Nombre}
DefaultDirName={autopf}\{#Nombre}
DefaultGroupName={#Nombre}
DisableProgramGroupPage=yes
; Por defecto se instala solo para el usuario actual (sin permisos de administrador);
; el asistente permite elegir "para todos los usuarios"
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#Salida}
OutputBaseFilename=SmartStock-IoT-{#Version}-instalador
SetupIconFile=smartstock.ico
UninstallDisplayIcon={app}\{#Ejecutable}
UninstallDisplayName={#Nombre}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes

[Languages]
Name: "es"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "escritorio"; Description: "Crear un acceso directo en el escritorio"; GroupDescription: "Accesos directos:"
Name: "firewall"; Description: "Permitir que el ESP32 se conecte por la red (regla del Firewall de Windows)"; GroupDescription: "Red:"; Check: IsAdminInstallMode

[Files]
Source: "{#Origen}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#Nombre}"; Filename: "{app}\{#Ejecutable}"
Name: "{group}\Carpeta de configuracion y registro"; Filename: "{%USERPROFILE}\.smartstock"
Name: "{group}\Desinstalar {#Nombre}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#Nombre}"; Filename: "{app}\{#Ejecutable}"; Tasks: escritorio

[Run]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""{#Nombre}"" dir=in action=allow program=""{app}\{#Ejecutable}"" enable=yes profile=private,domain"; Flags: runhidden; Tasks: firewall
Filename: "{app}\{#Ejecutable}"; Description: "Abrir {#Nombre}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""{#Nombre}"""; Flags: runhidden; RunOnceId: "QuitarFirewall"; Check: IsAdminInstallMode

[Code]
// PostgreSQL es requisito: se avisa (sin bloquear) si no se encuentra el servicio
function PostgresInstalado(): Boolean;
var
  Nombres: TArrayOfString;
begin
  Result := RegGetSubkeyNames(HKLM64, 'SOFTWARE\PostgreSQL\Installations', Nombres) and (GetArrayLength(Nombres) > 0);
end;

function InitializeSetup(): Boolean;
begin
  Result := True;
  if not PostgresInstalado() then
    Result := MsgBox('No se detecto PostgreSQL en este equipo.' + #13#10#13#10 +
      'SmartStock IoT guarda sus datos en PostgreSQL 16 o superior. Puede instalarlo despues ' +
      'desde https://www.postgresql.org/download/windows/ ; al abrir SmartStock por primera vez ' +
      'un asistente configurara la base de datos.' + #13#10#13#10 + 'Desea continuar con la instalacion?',
      mbConfirmation, MB_YESNO) = IDYES;
end;
