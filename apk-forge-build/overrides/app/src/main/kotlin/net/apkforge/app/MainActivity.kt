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
import net.apkforge.app.storage.ProjectStorage
import net.apkforge.core.apk.ApkInspector
import net.apkforge.core.model.ProjectAnalysis
import net.apkforge.core.model.ProjectType
import net.apkforge.core.project.ProjectAnalyser
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class MainActivity:ComponentActivity(){ override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme(colorScheme=lightColorScheme()){ApkForgeHome(this)}}} }

data class ProjectSummary(val name:String,val kind:String,val detail:String,val root:File,val applicationId:String?=null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ApkForgeHome(context:Context){
    var tab by remember{mutableStateOf("Projects")}
    var importUri by remember{mutableStateOf<Uri?>(null)}
    var summary by remember{mutableStateOf<ProjectSummary?>(null)}
    var error by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    var buildBusy by remember{mutableStateOf(false)}
    var buildStatus by remember{mutableStateOf("No build started")}
    val scope=rememberCoroutineScope()
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)importUri=uri}

    fun startBuild(s:ProjectSummary){
        if(buildBusy)return
        tab="Builds"
        buildBusy=true
        buildStatus="Analysing build inputs…"
        scope.launch{
            buildStatus=try{withContext(Dispatchers.IO){buildPreflight(s)}}catch(t:Throwable){"Build preflight failed: ${t.message?:t.javaClass.simpleName}"}
            buildBusy=false
        }
    }

    LaunchedEffect(importUri){
        val uri=importUri?:return@LaunchedEffect
        busy=true;error=null;summary=null;buildStatus="No build started"
        try{summary=withContext(Dispatchers.IO){importAndAnalyse(context,uri)}}catch(t:Throwable){error=t.message?:t.javaClass.simpleName}finally{busy=false;importUri=null}
    }

    Scaffold(topBar={TopAppBar(title={Text("APK Forge")})},bottomBar={NavigationBar{listOf("Projects","Builds","Toolchains","Settings").forEach{item->NavigationBarItem(tab==item,{tab=item},icon={},label={Text(item)})}}}){pad->
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
                                s.applicationId?.let{Text(it,style=MaterialTheme.typography.labelMedium)}
                                Text("Workspace: ${s.root.name}",style=MaterialTheme.typography.labelSmall)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                    Button(onClick={startBuild(s)},enabled=!buildBusy){Text(if(buildBusy)"Checking…" else "Start build")}
                                    OutlinedButton(onClick={tab="Builds"}){Text("Build details")}
                                }
                            }
                        }
                    }?:ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("No project selected");Text("Import an APK or project ZIP to begin.",style=MaterialTheme.typography.bodyMedium)}}
                }
                "Builds"->{
                    val s=summary
                    if(s==null){
                        Text("No project selected. Import an APK or ZIP from Projects first.")
                        Button(onClick={tab="Projects"}){Text("Go to Projects")}
                    }else{
                        ElevatedCard(Modifier.fillMaxWidth()){
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                Text(s.name,style=MaterialTheme.typography.titleMedium)
                                Text("Build pipeline: prepare → validate → decode/compile → package → align → sign → verify.")
                                Button(onClick={startBuild(s)},enabled=!buildBusy){Text(if(buildBusy)"Checking build…" else "Start build")}
                                Text(buildStatus,style=MaterialTheme.typography.bodyMedium,color=if(buildStatus.startsWith("Build blocked")||buildStatus.startsWith("Build preflight failed"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
                "Toolchains"->{
                    Text("Local Apktool/Smali builds require Smali, AAPT2, zipalign, apksigner and android.jar; Java extensions also require javac and D8.")
                    Text("Native Gradle/Compose projects are identified separately and require the Gradle/remote build path.",style=MaterialTheme.typography.bodyMedium)
                }
                else->Text("Build mode: Automatic. Remote builds are opt-in and signing remains local by default.")
            }
        }
    }
}

private fun buildPreflight(s:ProjectSummary):String{
    if(s.kind=="APK"){
        val input=s.root.resolve("input.apk")
        if(!input.isFile)return "Build blocked: imported APK payload is missing."
        val info=ApkInspector.inspect(input.toPath())
        return "Build blocked: ${info.dexEntries.size}-DEX APK import is valid, but APK decode is not yet wired into this Android build."
    }
    val analysis=ProjectAnalyser().analyse(s.root.toPath())
    if(analysis.type==ProjectType.UNKNOWN)return "Build blocked: project type could not be analysed."
    val app=analysis.application ?: return "Build blocked: Android application metadata is incomplete after project-root discovery."
    if(analysis.type==ProjectType.GRADLE_ANDROID){
        return "Gradle project resolved: ${app.applicationId} ${app.versionName} (${app.versionCode}), minSdk ${app.minSdk}, targetSdk ${app.targetSdk}. Build blocked only because the Gradle/Compose execution backend is not yet connected in this build."
    }
    val tools=mutableListOf("Smali","AAPT2","zipalign","apksigner","android.jar")
    if(analysis.javaFiles>0)tools+=listOf("javac","D8")
    return "Project metadata resolved: ${app.applicationId} ${app.versionName} (${app.versionCode}). Local build preflight passed. Required toolchain: ${tools.joinToString()}."
}

private fun importAndAnalyse(context:Context,uri:Uri):ProjectSummary{
    val name=displayName(context,uri)?:"import.bin"
    val imported=ProjectStorage(context).importFile(uri,name)
    if(name.endsWith(".apk",true)){
        val i=ApkInspector.inspect(imported.root.resolve("input.apk").toPath())
        return ProjectSummary(name,"APK","${i.dexEntries.size} DEX · ABIs ${i.nativeAbis.ifEmpty{setOf("none")}.joinToString()} · SHA-256 ${i.sha256.take(16)}…",imported.root)
    }
    val analyser=ProjectAnalyser()
    val resolved=findBestProjectRoot(imported.root.toPath(),analyser)
    val analysis=analyser.analyse(resolved)
    val app=analysis.application
    val identity=app?.let{"${it.applicationId} · ${it.versionName} (${it.versionCode}) · SDK ${it.minSdk}→${it.targetSdk}"} ?: "metadata unresolved"
    val detail=when(analysis.type){
        ProjectType.GRADLE_ANDROID -> "Gradle Android · $identity · ${analysis.javaFiles} Java/Kotlin · ${analysis.resourceFiles} resources · ${analysis.totalFiles} files"
        else -> "${analysis.dexTrees.size} Smali DEX trees · ${analysis.javaFiles} Java/Kotlin · ${analysis.resourceFiles} resources · ${analysis.totalFiles} files · $identity"
    }
    return ProjectSummary(name,analysis.type.wire,detail,resolved.toFile(),app?.applicationId)
}

private fun findBestProjectRoot(importRoot:Path,analyser:ProjectAnalyser):Path{
    data class Candidate(val path:Path,val analysis:ProjectAnalysis,val score:Int)
    fun score(a:ProjectAnalysis,depth:Int):Int{
        val typeScore=when(a.type){
            ProjectType.GRADLE_ANDROID->500
            ProjectType.APKTOOL_SMALI->450
            ProjectType.JAVA_ANDROID->350
            ProjectType.APK->300
            ProjectType.UNKNOWN->0
        }
        return typeScore+(if(a.application!=null)200 else 0)+(if(a.resourceFiles>0)20 else 0)+(if(a.dexTrees.isNotEmpty())20 else 0)-depth
    }
    val candidates=mutableListOf<Candidate>()
    Files.walk(importRoot,7).use{stream->
        stream.filter(Files::isDirectory).forEach{p->
            val a=runCatching{analyser.analyse(p)}.getOrNull()?:return@forEach
            if(a.type!=ProjectType.UNKNOWN){
                val depth=importRoot.relativize(p).nameCount
                candidates+=Candidate(p,a,score(a,depth))
            }
        }
    }
    return candidates.maxWithOrNull(compareBy<Candidate>{it.score}.thenBy{-importRoot.relativize(it.path).nameCount})?.path ?: importRoot
}

private fun displayName(context:Context,uri:Uri):String?{context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())return c.getString(0)};return uri.lastPathSegment}
