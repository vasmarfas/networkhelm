; The Windows installer of NetworkHelm, built by Inno Setup 6.7 from the app image Compose makes. The task
; :app:desktopApp:packageInnoSetup passes AppVersion, AppVersionName, AppImage and OutputDir and copies the result under a portable
; name. The wizard follows the light or dark mode of Windows.
;
; The same file also runs NetworkHelm without installing it: chosen on the first page, asked for by /PORTABLE, or named
; *portable*.exe. The first start of a version unpacks it once into a folder of its own in the local application data,
; with only the progress shown, and every later start runs it from there at once. Nothing goes to the Start menu or the
; list of installed programs, and the data stays in the folder "NetworkHelm data" beside the file. The file is saved with
; a BOM, which Inno Setup needs for the Russian texts.
#ifndef AppVersion
  #error AppVersion, AppVersionName, AppImage and OutputDir are passed by packageInnoSetup
#endif
; /DLegacy=1 makes the flavour for Windows 7 and 8, /DArm64=1 the one for Windows on ARM: another runtime, the same installer
#ifdef Legacy
  #define Flavor "-legacy"
#else
  #ifdef Arm64
    #define Flavor "-arm64"
  #else
    #define Flavor ""
  #endif
#endif

[Setup]
AppId={{6B2F6E1C-4C58-4E0A-9D35-2C7A3E1D8F41}
AppName=NetworkHelm
AppVersion={#AppVersion}
AppVerName=NetworkHelm {#AppVersionName}
AppPublisher=vasmarfas
AppCopyright=Copyright vasmarfas
VersionInfoVersion={#AppVersion}
DefaultDirName={autopf}\NetworkHelm
DefaultGroupName=NetworkHelm
DisableProgramGroupPage=yes
; for the current user, as the MSI; /ALLUSERS installs for everyone with administrator rights
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=commandline
#ifdef Arm64
ArchitecturesAllowed=arm64
ArchitecturesInstallIn64BitMode=arm64
#else
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#endif
#ifdef Legacy
MinVersion=6.1sp1
#else
MinVersion=10.0
#endif
; the class data archives of the image hold the time of every jar and are skipped if one differs; rounded down to two
; seconds, as Inno Setup does by default, a jar written at an odd second would cost the start 0.6 s
TimeStampRounding=0
Uninstallable=not IsPortable
CreateUninstallRegKey=not IsPortable
LicenseFile=..\..\..\LICENSE
SetupIconFile=..\icons\networkhelm.ico
UninstallDisplayIcon={app}\NetworkHelm.exe
UninstallDisplayName=NetworkHelm
WizardStyle=modern dynamic windows11 includetitlebar hidebevels
; the first page shows the picture of the wizard, as the last one does
DisableWelcomePage=no
; the pictures are dark and serve both modes; without the DynamicDark ones the dark mode puts its own in their place
WizardImageFile=wizard-202.png,wizard-269.png,wizard-336.png,wizard-403.png,wizard-430.png,wizard-534.png
WizardImageFileDynamicDark=wizard-202.png,wizard-269.png,wizard-336.png,wizard-403.png,wizard-430.png,wizard-534.png
WizardSmallImageFile=small-58.png,small-72.png,small-87.png,small-101.png,small-116.png,small-145.png
WizardSmallImageFileDynamicDark=small-58.png,small-72.png,small-87.png,small-101.png,small-116.png,small-145.png
ShowLanguageDialog=auto
CloseApplications=yes
RestartApplications=no
Compression=lzma2/max
SolidCompression=yes
OutputDir={#OutputDir}
OutputBaseFilename=NetworkHelm-{#AppVersionName}{#Flavor}-setup

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"

[Messages]
#ifdef Legacy
en.WindowsVersionNotSupported=This flavour of NetworkHelm needs Windows 7 with Service Pack 1 or newer (64-bit); this computer runs an older version of Windows.
ru.WindowsVersionNotSupported=Этой сборке NetworkHelm нужна Windows 7 с Service Pack 1 и новее (64-разрядная); на этом компьютере стоит более старая версия Windows.
#else
en.WindowsVersionNotSupported=NetworkHelm needs Windows 10 or newer (64-bit); this computer runs an older version of Windows. For 64-bit Windows 7 SP1 and 8 the release has a file marked legacy.
ru.WindowsVersionNotSupported=NetworkHelm работает на Windows 10 и новее (64-разрядной); на этом компьютере стоит более старая версия Windows. Для 64-разрядных Windows 7 SP1 и 8 в релизе есть файл с пометкой legacy.
#endif

[CustomMessages]
en.ModeTitle=How to start NetworkHelm
en.ModeDescription=Install it, or run it from this file without installing.
en.ModeText=Without installing, NetworkHelm unpacks itself once into a folder of its own in the local application data and starts from there at once afterwards. It is not added to the Start menu or to the installed programs; settings and saved routers stay in the folder "NetworkHelm data" beside this file.
en.ModeInstall=Install on this computer
en.ModePortable=Run without installing (portable)
en.Unpacking=Unpacking NetworkHelm
en.UnpackingText=This happens once for this version; NetworkHelm starts as soon as it is unpacked.
ru.ModeTitle=Как запустить NetworkHelm
ru.ModeDescription=Установить или запустить из этого файла без установки.
ru.ModeText=Без установки NetworkHelm один раз распаковывается в свою папку в локальных данных приложений и дальше запускается из неё сразу. В меню «Пуск» и в списке установленных программ он не появляется; настройки и сохранённые роутеры остаются в папке «NetworkHelm data» рядом с этим файлом.
ru.ModeInstall=Установить на этот компьютер
ru.ModePortable=Запустить без установки (портативно)
ru.Unpacking=Распаковка NetworkHelm
ru.UnpackingText=Это происходит один раз для этой версии; NetworkHelm запустится, как только распакуется.

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked; Check: not IsPortable

[InstallDelete]
; the jars and the files of the web page carry a hash in their names, and those of the version before would stay
Type: filesandordirs; Name: "{app}\app"

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\NetworkHelm"; Filename: "{app}\NetworkHelm.exe"; Check: not IsPortable
Name: "{autodesktop}\NetworkHelm"; Filename: "{app}\NetworkHelm.exe"; Tasks: desktopicon; Check: not IsPortable

[Run]
Filename: "{app}\NetworkHelm.exe"; Description: "{cm:LaunchProgram,NetworkHelm}"; Flags: nowait postinstall skipifsilent; Check: not IsPortable

[Code]
var
  ModePage: TInputOptionWizardPage;
  Portable: Boolean;

function SetEnvironmentVariable(Name: String; Value: String): BOOL;
  external 'SetEnvironmentVariableW@kernel32.dll stdcall';

function IsPortable: Boolean;
begin
  Result := Portable;
end;

// a file named *portable*.exe, or one started with /PORTABLE, runs NetworkHelm at once
function PortableAsked: Boolean;
var
  I: Integer;
begin
  Result := Pos('portable', Lowercase(ExtractFileName(ExpandConstant('{srcexe}')))) > 0;
  for I := 1 to ParamCount do
    if CompareText(ParamStr(I), '/PORTABLE') = 0 then
      Result := True;
end;

// the unpacked copy of this version, kept for the next start
function PortableDir: String;
begin
  Result := ExpandConstant('{localappdata}\NetworkHelm\portable\{#AppVersionName}{#Flavor}');
end;

function StartPortable: Boolean;
var
  Code: Integer;
begin
  SetEnvironmentVariable('NETWORKHELM_HOME', ExpandConstant('{src}\NetworkHelm data'));
  Result := Exec(PortableDir + '\NetworkHelm.exe', '', ExpandConstant('{src}'), SW_SHOWNORMAL, ewNoWait, Code);
  if not Result then
    Log('Could not start NetworkHelm: ' + SysErrorMessage(Code));
end;

// the copies of other versions go, each takes some 200 MB
procedure DropOtherCopies;
var
  Found: TFindRec;
  Root: String;
begin
  Root := ExpandConstant('{localappdata}\NetworkHelm\portable');
  if FindFirst(Root + '\*', Found) then
    try
      repeat
        if (Found.Attributes and FILE_ATTRIBUTE_DIRECTORY <> 0) and (Found.Name <> '.') and (Found.Name <> '..') and (Found.Name <> '{#AppVersionName}{#Flavor}') then
          DelTree(Root + '\' + Found.Name, True, True, True);
      until not FindNext(Found);
    finally
      FindClose(Found);
    end;
end;

// A version unpacked before starts at once. Setup always shows its Ready page when no page came before it, so the
// first portable start runs itself again silently, with only the progress of unpacking shown; Setup may not start its
// own file, cmd may.
function InitializeSetup: Boolean;
var
  Code: Integer;
begin
  Result := True;
  if not PortableAsked then
    Exit;
  if FileExists(PortableDir + '\.unpacked') and StartPortable then
    Result := False
  else if not WizardSilent then
  begin
    Exec(ExpandConstant('{cmd}'), '/c start "" "' + ExpandConstant('{srcexe}') + '" /SILENT /SUPPRESSMSGBOXES /PORTABLE /LANG=' + ActiveLanguage, '', SW_HIDE, ewNoWait, Code);
    Result := False;
  end;
end;

procedure UsePortable;
begin
  Portable := True;
  WizardForm.DirEdit.Text := PortableDir;
end;

procedure InitializeWizard;
begin
  ModePage := CreateInputOptionPage(wpWelcome, CustomMessage('ModeTitle'), CustomMessage('ModeDescription'), CustomMessage('ModeText'), True, False);
  ModePage.Add(CustomMessage('ModeInstall'));
  ModePage.Add(CustomMessage('ModePortable'));
  ModePage.SelectedValueIndex := 0;
  if PortableAsked then
    UsePortable;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  if (CurPageID = ModePage.ID) and (ModePage.SelectedValueIndex = 1) then
    UsePortable;
  Result := True;
end;

// a portable start goes from the choice straight to unpacking and ends with NetworkHelm started
function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := Portable and (PageID <> ModePage.ID);
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if Portable and (CurPageID = wpInstalling) then
  begin
    WizardForm.PageNameLabel.Caption := CustomMessage('Unpacking');
    WizardForm.PageDescriptionLabel.Caption := CustomMessage('UnpackingText');
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if Portable and (CurStep = ssPostInstall) then
  begin
    SaveStringToFile(PortableDir + '\.unpacked', '{#AppVersionName}', False);
    DropOtherCopies;
    StartPortable;
  end;
end;
