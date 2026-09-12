package net.apkforge.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.apkforge.app.build.EmbeddedApktoolEngine
import net.apkforge.app.storage.ProjectStorage
import net.apkforge.core.apk.ApkInspector
import net.apkforge.core.project.ProjectAnalyser
import java.io.File
import java.nio.file.Files

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        setContent{MaterialTheme(colorScheme=lightColorScheme()){ApkForgeHome(this)}}
    }
}

data class ProjectSummary(val name:String,val kind:String,val detail:String,val root:File)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ApkForgeHome(context:Context){
    var tab by remember{mutableStateOf("Projects")}
    var importUri by remember{mutableStateOf<Uri?>(null)}
    var summary by remember{mutableStateOf<ProjectSummary?>(null)}
    var error by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    var buildBusy by remember{mutableStateOf(false)}
    var buildStatus by remember{mutableStateOf("No build started")}
    var lastBuild by remember{mutableStateOf<File?>(null)}
    val scope=rememberCoroutineScope()

    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)importUri=uri}
    val exportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")){uri->
        val built=lastBuild
        if(uri!=null&&built!=null){
            scope.launch{
                buildStatus=try{
                    withContext(Dispatchers.IO){
                        context.contentResolver.openOutputStream(uri,"w").use{out->requireNotNull(out){"Unable to open export destination"};built.inputStream().use{it.copyTo(out)}}
                    }
                    "Export complete: ${built.name}"
                }catch(t:Throwable){"Export failed: ${t.message?:t.javaClass.simpleName}"}
            }
        }
    }

    fun startBuild(s:ProjectSummary){
        if(buildBusy)return
        tab="Builds"
        buildBusy=true
        lastBuild=null
        buildStatus="Starting local build…"
        scope.launch{
            try{
                val result=withContext(Dispatchers.IO){
                    val engine=EmbeddedApktoolEngine(context)
                    val stage:(String)->Unit={message->scope.launch{buildStatus=message}}
                    when(s.kind.lowercase()){
                        "apk"->engine.buildImportedApk(s.root.resolve("input.apk"),s.root,s.name,stage)
                        "apktool-smali"->engine.buildDecoded(s.root,s.name,stage)
                        else->error("Local build is not yet supported for project type ${s.kind}")
                    }
                }
                lastBuild=result.apk
                buildStatus="Build complete and signature verified · ${result.apk.name} · SHA-256 ${result.sha256.take(16)}…"
            }catch(t:Throwable){
                buildStatus="Build failed: ${t.message?:t.javaClass.simpleName}"
            }finally{
                buildBusy=false
            }
        }
    }

    LaunchedEffect(importUri){
        val uri=importUri?:return@LaunchedEffect
        busy=true;error=null;summary=null;lastBuild=null;buildStatus="No build started"
        try{summary=withContext(Dispatchers.IO){importAndAnalyse(context,uri)}}
        catch(t:Throwable){error=t.message?:t.javaClass.simpleName}
        finally{busy=false;importUri=null}
    }

    Scaffold(
        topBar={TopAppBar(title={Text("APK Forge")})},
        bottomBar={NavigationBar{listOf("Projects","Builds","Toolchains","Settings").forEach{item->NavigationBarItem(tab==item,{tab=item},icon={},label={Text(item)})}}}
    ){pad->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(tab,style=MaterialTheme.typography.headlineMedium)
            when(tab){
                "Projects"->{
                    Button(onClick={launcher.launch(arrayOf("application/zip","application/vnd.android.package-archive","application/octet-stream"))},enabled=!busy&&!buildBusy){Text(if(busy)"Importing…" else "Import APK / ZIP")}
                    error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                    summary?.let{s->
                        ElevatedCard(Modifier.fillMaxWidth()){
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                Text(s.name,style=MaterialTheme.typography.titleLarge)
                                Text(s.kind)
                                Text(s.detail,style=MaterialTheme.typography.bodyMedium)
                                Text("Workspace: ${s.root.name}",style=MaterialTheme.typography.labelSmall)
                                Button(onClick={startBuild(s)},enabled=!buildBusy,modifier=Modifier.fillMaxWidth()){
                                    Text(if(buildBusy)"Building…" else if(lastBuild!=null)"Rebuild APK" else "Build APK")
                                }
                                OutlinedButton(onClick={tab="Builds"},modifier=Modifier.fillMaxWidth()){Text("Build details")}
                            }
                        }
                    }?:ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("No project selected");Text("Import an APK or Apktool project ZIP to begin.",style=MaterialTheme.typography.bodyMedium)}}
                }
                "Builds"->{
                    val s=summary
                    if(s==null){
                        Text("No project selected. Import an APK or ZIP from Projects first.")
                        Button(onClick={tab="Projects"}){Text("Go to Projects")}
                    }else{
                        ElevatedCard(Modifier.fillMaxWidth()){
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                                Text(s.name,style=MaterialTheme.typography.titleMedium)
                                Text("Local pipeline: APK decode → Smali/resources rebuild → device-local sign → signature verify.")
                                Button(onClick={startBuild(s)},enabled=!buildBusy,modifier=Modifier.fillMaxWidth()){
                                    Text(if(buildBusy)"Build in progress…" else if(lastBuild!=null)"Rebuild APK" else "Start build")
                                }
                                val failed=buildStatus.startsWith("Build failed")||buildStatus.startsWith("Export failed")
                                Text(buildStatus,style=MaterialTheme.typography.bodyMedium,color=if(failed)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                lastBuild?.let{built->
                                    Text("Output: ${built.name}\n${built.length()} bytes",style=MaterialTheme.typography.labelMedium)
                                    Button(onClick={exportLauncher.launch(built.name)},modifier=Modifier.fillMaxWidth()){Text("Export built APK")}
                                }
                            }
                        }
                    }
                }
                "Toolchains"->{
                    Text("Embedded local build engine",style=MaterialTheme.typography.titleMedium)
                    Text("Apktool 2.12.1 runs in-process. APK Forge packages an ABI-matched AAPT2 executable for Android resource rebuilding and uses Android apksig for local signing and verification.")
                    Text("Current local build target: imported APKs and Apktool/Smali project ZIPs. Java/Gradle projects remain outside this embedded path.",style=MaterialTheme.typography.bodyMedium)
                }
                else->{
                    Text("Build mode: Local embedded",style=MaterialTheme.typography.titleMedium)
                    Text("Signing identity is generated and retained in AndroidKeyStore. Built APKs stay in app-private storage until you export them.")
                }
            }
        }
    }
}

private fun importAndAnalyse(context:Context,uri:Uri):ProjectSummary{
    val name=displayName(context,uri)?:"import.bin"
    val imported=ProjectStorage(context).importFile(uri,name)
    if(name.endsWith(".apk",true)){
        val i=ApkInspector.inspect(imported.root.resolve("input.apk").toPath())
        return ProjectSummary(name,"APK","${i.dexEntries.size} DEX · ABIs ${i.nativeAbis.ifEmpty{setOf("none")}.joinToString()} · SHA-256 ${i.sha256.take(16)}…",imported.root)
    }
    var root=imported.root.toPath();var analysis=ProjectAnalyser().analyse(root)
    if(analysis.type.name=="UNKNOWN"){
        val dirs=Files.list(root).use{s->s.filter(Files::isDirectory).iterator().asSequence().toList()}
        if(dirs.size==1){val nested=ProjectAnalyser().analyse(dirs.single());if(nested.type.name!="UNKNOWN"){root=dirs.single();analysis=nested}}
    }
    return ProjectSummary(name,analysis.type.wire,"${analysis.dexTrees.size} Smali DEX trees · ${analysis.javaFiles} Java · ${analysis.resourceFiles} resources · ${analysis.totalFiles} files",root.toFile())
}

private fun displayName(context:Context,uri:Uri):String?{
    context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())return c.getString(0)}
    return uri.lastPathSegment
}
