$ErrorActionPreference = "Stop"
$utf8 = [System.Text.UTF8Encoding]::new($false)
$nl = [string][char]10
function Read-Lf([string]$Path) { return [IO.File]::ReadAllText((Join-Path $PWD $Path)).Replace([Environment]::NewLine,$nl) }
function Write-Lf([string]$Path,[string]$Value) { [IO.File]::WriteAllText((Join-Path $PWD $Path),$Value,$utf8) }
function Replace-Exactly([string]$Text,[string]$Old,[string]$New,[string]$Label) {
  $nlLocal=[string][char]10
  $Old=$Old.Replace([Environment]::NewLine,$nlLocal)
  $New=$New.Replace([Environment]::NewLine,$nlLocal)
  $count=0; $offset=0
  while($true){$found=$Text.IndexOf($Old,$offset,[StringComparison]::Ordinal);if($found -lt 0){break};$count++;$offset=$found+$Old.Length}
  if($count -ne 1){throw "$Label expected one anchor, found $count"}
  return $Text.Replace($Old,$New)
}

$g=Read-Lf "app/build.gradle"
$g=Replace-Exactly $g ("android {"+$nl) ("android {"+$nl+"    buildFeatures { buildConfig true }"+$nl) "BuildConfig feature"
$old=@'
    buildTypes {
        debug { applicationIdSuffix '.debug'; versionNameSuffix '-debug' }
        release {
            minifyEnabled false
            if (releaseSigningReady) signingConfig signingConfigs.release
        }
    }
}
dependencies {
'@
$new=@'
    buildTypes {
        debug {
            applicationIdSuffix '.debug'
            versionNameSuffix '-debug'
            buildConfigField 'boolean', 'WAL_OFFICIAL', 'false'
            manifestPlaceholders = [appLabel: 'WAL Development']
        }
        release {
            minifyEnabled false
            buildConfigField 'boolean', 'WAL_OFFICIAL', 'false'
            manifestPlaceholders = [appLabel: 'WAL Development']
            if (releaseSigningReady) signingConfig signingConfigs.release
        }
        official {
            initWith release
            applicationIdSuffix '.wal'
            buildConfigField 'boolean', 'WAL_OFFICIAL', 'true'
            manifestPlaceholders = [appLabel: 'WAL Android Launcher']
        }
    }
}
androidComponents {
    onVariants(selector().withBuildType("official")) { variant ->
        variant.outputs.each { output ->
            output.versionCode.set(1)
            output.versionName.set("1.0.0")
        }
    }
}
dependencies {
'@
$g=Replace-Exactly $g $old $new "Build types"
Write-Lf "app/build.gradle" $g

$m=Read-Lf "app/src/main/AndroidManifest.xml"
$label='android:label="' + '$' + '{appLabel}"'
$m=Replace-Exactly $m 'android:label="WAL"' $label "Manifest label"
Write-Lf "app/src/main/AndroidManifest.xml" $m

$main=Read-Lf "app/src/main/java/com/wayward/launcher/MainActivity.java"
$main=Replace-Exactly $main ("        registerRemotePcControl();"+$nl) ("        if(!BuildConfig.WAL_OFFICIAL)registerRemotePcControl();"+$nl) "Remote control registration"
$main=Replace-Exactly $main ("            startRemoteHostDiscoveryIfNeeded();"+$nl+"            reconnectRemoteHostInBackground();"+$nl) ("            if(!BuildConfig.WAL_OFFICIAL){"+$nl+"                startRemoteHostDiscoveryIfNeeded();"+$nl+"                reconnectRemoteHostInBackground();"+$nl+"            }"+$nl) "Remote discovery"
$oldHeader=@'
        Button pcFolder=compactButton("+ PC 폴더",false,this::chooseRemotePcFolder);
        LinearLayout.LayoutParams pcFolderParams=new LinearLayout.LayoutParams(dp(76),dp(38));
        pcFolderParams.leftMargin=dp(4);top.addView(pcFolder,pcFolderParams);
        Button addFolder=compactButton("+ 현재 기기 폴더",true,this::chooseFolder);
        LinearLayout.LayoutParams addFolderParams=new LinearLayout.LayoutParams(dp(112),dp(38));
        addFolderParams.leftMargin=dp(4);top.addView(addFolder,addFolderParams);
        pairingButton=compactButton("페어링",false,this::openRemotePairing);
        LinearLayout.LayoutParams pairingParams=new LinearLayout.LayoutParams(dp(76),dp(38));
        pairingParams.leftMargin=dp(4);top.addView(pairingButton,pairingParams);
        Button diagnostics=compactButton("진단",false,this::shareDiagnosticsZip);
'@
$newHeader=@'
        if(!BuildConfig.WAL_OFFICIAL){
            Button pcFolder=compactButton("+ PC 폴더",false,this::chooseRemotePcFolder);
            LinearLayout.LayoutParams pcFolderParams=new LinearLayout.LayoutParams(dp(76),dp(38));
            pcFolderParams.leftMargin=dp(4);top.addView(pcFolder,pcFolderParams);
            Button addFolder=compactButton("+ 현재 기기 폴더",true,this::chooseFolder);
            LinearLayout.LayoutParams addFolderParams=new LinearLayout.LayoutParams(dp(112),dp(38));
            addFolderParams.leftMargin=dp(4);top.addView(addFolder,addFolderParams);
            pairingButton=compactButton("페어링",false,this::openRemotePairing);
            LinearLayout.LayoutParams pairingParams=new LinearLayout.LayoutParams(dp(76),dp(38));
            pairingParams.leftMargin=dp(4);top.addView(pairingButton,pairingParams);
        }else{
            Button addFolder=compactButton("+ 현재 기기 폴더",true,this::chooseFolder);
            LinearLayout.LayoutParams addFolderParams=new LinearLayout.LayoutParams(dp(112),dp(38));
            addFolderParams.leftMargin=dp(4);top.addView(addFolder,addFolderParams);
        }
        Button diagnostics=compactButton("진단",false,this::shareDiagnosticsZip);
'@
$main=Replace-Exactly $main $oldHeader $newHeader "Home header controls"
$main=Replace-Exactly $main 'uiText("Wayward Android Launcher",11,Color.rgb(111,118,125),false)' 'uiText(BuildConfig.WAL_OFFICIAL?"WAL Android Launcher":"Wayward Android Launcher",11,Color.rgb(111,118,125),false)' "Home subtitle"
$oldManager=@'
        Button remoteFileManager=compactButton("원격 PC 파일매니저",false,this::openRemotePcFileManager);
        LinearLayout.LayoutParams remoteFileManagerParams=new LinearLayout.LayoutParams(-1,dp(46));
        remoteFileManagerParams.topMargin=dp(12);
        page.addView(remoteFileManager,remoteFileManagerParams);
'@
$newManager=@'
        if(!BuildConfig.WAL_OFFICIAL){
            Button remoteFileManager=compactButton("원격 PC 파일매니저",false,this::openRemotePcFileManager);
            LinearLayout.LayoutParams remoteFileManagerParams=new LinearLayout.LayoutParams(-1,dp(46));
            remoteFileManagerParams.topMargin=dp(12);
            page.addView(remoteFileManager,remoteFileManagerParams);
        }
'@
$main=Replace-Exactly $main $oldManager $newManager "Remote file manager"
$oldMode=@'
    private String currentImageMode(){
        return getSharedPreferences(PREFS,MODE_PRIVATE).getString(KEY_IMAGE_MODE,IMAGE_MODE_PACKS_ONLY);
    }
'@
$newMode=@'
    private String currentImageMode(){
        String mode=getSharedPreferences(PREFS,MODE_PRIVATE).getString(KEY_IMAGE_MODE,IMAGE_MODE_PACKS_ONLY);
        if(BuildConfig.WAL_OFFICIAL&&IMAGE_MODE_REMOTE_PC.equals(mode))return IMAGE_MODE_PACKS_ONLY;
        return mode;
    }
'@
$main=Replace-Exactly $main $oldMode $newMode "Official image mode fallback"
$main=Replace-Exactly $main '        LinearLayout.LayoutParams serverParams=new LinearLayout.LayoutParams(0,dp(82),1f);' '        LinearLayout.LayoutParams serverParams=BuildConfig.WAL_OFFICIAL?new LinearLayout.LayoutParams(-1,dp(82)):new LinearLayout.LayoutParams(0,dp(82),1f);' "Official server width"
$oldComfy=@'
        boolean remotePaired=RemoteHostStore.isPaired(this);
        boolean comfySelected=IMAGE_MODE_REMOTE_PC.equals(mode)&&remotePaired;
        View comfy=glassModeTile(comfySelected?"ComfyUI 연결됨":"ComfyUI 서버 실행",
                remotePaired?"PC Host 인증 · ComfyUI 권한 필요":"PC 페어링 필요",
                comfySelected,null,this::switchToRemoteComfyMode);
        LinearLayout.LayoutParams comfyParams=new LinearLayout.LayoutParams(0,dp(82),1f);
        comfyParams.leftMargin=dp(8);
        serverRow.addView(comfy,comfyParams);
'@
$newComfy=@'
        if(!BuildConfig.WAL_OFFICIAL){
            boolean remotePaired=RemoteHostStore.isPaired(this);
            boolean comfySelected=IMAGE_MODE_REMOTE_PC.equals(mode)&&remotePaired;
            View comfy=glassModeTile(comfySelected?"ComfyUI 연결됨":"ComfyUI 서버 실행",
                    remotePaired?"PC Host 인증 · ComfyUI 권한 필요":"PC 페어링 필요",
                    comfySelected,null,this::switchToRemoteComfyMode);
            LinearLayout.LayoutParams comfyParams=new LinearLayout.LayoutParams(0,dp(82),1f);
            comfyParams.leftMargin=dp(8);
            serverRow.addView(comfy,comfyParams);
        }
'@
$main=Replace-Exactly $main $oldComfy $newComfy "ComfyUI control"
$main=Replace-Exactly $main '        if(pairingButton==null)return;' '        if(BuildConfig.WAL_OFFICIAL||pairingButton==null)return;' "Pairing state guard"
$main=Replace-Exactly $main ("    private void openRemotePairing(){"+$nl+"        if(RemoteHostStore.isPaired(this)){") ("    private void openRemotePairing(){"+$nl+"        if(BuildConfig.WAL_OFFICIAL)return;"+$nl+"        if(RemoteHostStore.isPaired(this)){") "Pairing entry guard"
$main=Replace-Exactly $main ("    private void chooseRemotePcFolder(){"+$nl+"        if(!RemoteHostStore.isPaired(this)){") ("    private void chooseRemotePcFolder(){"+$nl+"        if(BuildConfig.WAL_OFFICIAL)return;"+$nl+"        if(!RemoteHostStore.isPaired(this)){") "PC folder entry guard"
Write-Lf "app/src/main/java/com/wayward/launcher/MainActivity.java" $main

Write-Host "Product split source changes applied; CI workflow updates are handled separately with workflow-scoped credentials."
