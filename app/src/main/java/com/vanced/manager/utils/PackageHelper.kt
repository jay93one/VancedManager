package com.vanced.manager.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.vanced.manager.BuildConfig
import com.vanced.manager.core.App
import com.vanced.manager.core.installer.AppInstallerService
import com.vanced.manager.core.installer.AppUninstallerService
import com.vanced.manager.core.installer.SplitInstallerService
import com.vanced.manager.ui.viewmodels.HomeViewModel.Companion.vancedProgress
import com.vanced.manager.utils.AppUtils.mutableInstall
import com.vanced.manager.utils.AppUtils.sendFailure
import com.vanced.manager.utils.AppUtils.sendRefresh
import com.vanced.manager.utils.AppUtils.vancedRootPkg
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

object PackageHelper {
    
    private const val apkInstallPath = "/data/adb/Vanced/"

    fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun getPackageVersionName(packageName: String, packageManager: PackageManager): String {
        return if (isPackageInstalled(packageName, packageManager))
            packageManager.getPackageInfo(packageName, 0).versionName
        else
            ""
    }

    @Suppress("DEPRECATION")
    fun getPkgVerCode(pkg: String, pm:PackageManager): Int? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pm.getPackageInfo(pkg, 0)?.longVersionCode?.and(0xFFFFFFFF)?.toInt()
        else
            pm.getPackageInfo(pkg, 0)?.versionCode

    }

    fun uninstallApk(pkg: String, context: Context): Boolean {
        val callbackIntent = Intent(context, AppUninstallerService::class.java)
        callbackIntent.putExtra("pkg", pkg)
        val pendingIntent = PendingIntent.getService(context, 0, callbackIntent, 0)
        return try {
            context.packageManager.packageInstaller.uninstall(pkg, pendingIntent.intentSender)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun install(app: String, path: String, context: Context){
        val callbackIntent = Intent(context, AppInstallerService::class.java).putExtra("app", app)
        val pendingIntent = PendingIntent.getService(context, 0, callbackIntent, 0)
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(if (app == "microg") "com.mgoogle.android.gms" else "com.vanced.android.apps.youtube.music")
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        val inputStream: InputStream = FileInputStream(path)
        val outputStream = session.openWrite("install", 0, -1)
        val buffer = ByteArray(65536)
        var c: Int
        while (inputStream.read(buffer).also { c = it } != -1) {
            outputStream.write(buffer, 0, c)
        }
        session.fsync(outputStream)
        inputStream.close()
        outputStream.close()
        session.commit(pendingIntent.intentSender)
    }
    
    fun installVanced(context: Context): Int {
        val apkFolderPath = context.getExternalFilesDir("apks")?.path + "/"
        val nameSizeMap = HashMap<String, Long>()
        var totalSize: Long = 0
        var sessionId = 0
        val folder = File(apkFolderPath)
        val listOfFiles = folder.listFiles()
        try {
            for (listOfFile in listOfFiles!!) {
                if (listOfFile.isFile) {
                    Log.d("AppLog", "installApk: " + listOfFile.name)
                    nameSizeMap[listOfFile.name] = listOfFile.length()
                    totalSize += listOfFile.length()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return -1
        }
        val installParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        installParams.setSize(totalSize)
        try {
            sessionId = context.packageManager.packageInstaller.createSession(installParams)
            Log.d("AppLog","Success: created install session [$sessionId]")
            for ((key, value) in nameSizeMap) {
                doWriteSession(sessionId, apkFolderPath + key, value, key, context)
            }
            doCommitSession(sessionId, context)
            Log.d("AppLog","Success")
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return sessionId
    }

    private fun doWriteSession(sessionId: Int, inPath: String?, sizeBytes: Long, splitName: String, context: Context): Int {
        var inPathToUse = inPath
        var sizeBytesToUse = sizeBytes
        if ("-" == inPathToUse) {
            inPathToUse = null
        } else if (inPathToUse != null) {
            val file = File(inPathToUse)
            if (file.isFile)
                sizeBytesToUse = file.length()
        }
        var session: PackageInstaller.Session? = null
        var inputStream: InputStream? = null
        var out: OutputStream? = null
        try {
            session = context.packageManager.packageInstaller.openSession(sessionId)
            if (inPathToUse != null) {
                inputStream = FileInputStream(inPathToUse)
            }
            out = session.openWrite(splitName, 0, sizeBytesToUse)
            var total = 0
            val buffer = ByteArray(65536)
            var c: Int
            while (true) {
                c = inputStream!!.read(buffer)
                if (c == -1)
                    break
                total += c
                out.write(buffer, 0, c)
            }
            session.fsync(out)
            Log.d("AppLog", "Success: streamed $total bytes")
            return PackageInstaller.STATUS_SUCCESS
        } catch (e: IOException) {
            Log.e("AppLog", "Error: failed to write; " + e.message)
            return PackageInstaller.STATUS_FAILURE
        } finally {
            try {
                out?.close()
                inputStream?.close()
                session?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun doCommitSession(sessionId: Int, context: Context) {
        var session: PackageInstaller.Session? = null
        try {
            try {
                session = context.packageManager.packageInstaller.openSession(sessionId)
                val callbackIntent = Intent(context, SplitInstallerService::class.java)
                val pendingIntent = PendingIntent.getService(context, 0, callbackIntent, 0)
                session.commit(pendingIntent.intentSender)
                session.close()
                Log.d("AppLog", "install request sent")
                Log.d("AppLog", "doCommitSession: " + context.packageManager.packageInstaller.mySessions)
                Log.d("AppLog", "doCommitSession: after session commit ")
            } catch (e: IOException) {
                e.printStackTrace()
            }

        } finally {
            session!!.close()
        }
    }
    
    fun installVancedRoot(context: Context) {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )

        Shell.getShell {
            val application = context.applicationContext as App
            val vancedApplication = application.vanced.get()?.int("versionCode")
            val vancedVersionCode = if (vancedApplication != null) vancedApplication else { application.loadJsonAsync(); vancedApplication }
            val apkFilesPath = context.getExternalFilesDir("apks")?.path
            val fileInfoList = apkFilesPath?.let { it1 -> getFileInfoList(it1) }
            if (fileInfoList != null) {
                var modApk: FileInfo? = null
                for (file in fileInfoList) {
                    if (file.name == "dark.apk" || file.name == "black.apk") {
                        modApk = file
                    }
                }
                if (modApk != null) {
                    if (overwriteBase(modApk, fileInfoList, vancedVersionCode!!, context)) {
                        sendRefresh(context)
                        vancedProgress.get()?.showInstallCircle?.set(false)
                        mutableInstall.value = false
                    }
                }
                else {
                    sendFailure(listOf("ModApk_Missing").toMutableList(), context)
                }
            }
            else {
                sendFailure(listOf("Files_Missing_VA").toMutableList(), context)
            }

        }
    }
    
    private fun installSplitApkFiles(apkFiles: ArrayList<FileInfo>, context: Context) : Boolean {
        var sessionId: Int?
        Log.d("AppLog", "installing split apk files:$apkFiles")
        run {
            val sessionIdResult = Shell.su("pm install-create -r -t").exec().out
            val sessionIdPattern = Pattern.compile("(\\d+)")
            val sessionIdMatcher = sessionIdPattern.matcher(sessionIdResult[0])
            sessionIdMatcher.find()
            sessionId = Integer.parseInt(sessionIdMatcher.group(1)!!)
        }
        apkFiles.forEach { apkFile ->
            if (apkFile.name != "black.apk" && apkFile.name != "dark.apk" && apkFile.name != "hash.json") {
                Log.d("AppLog", "installing APK : ${apkFile.name} ${apkFile.fileSize} ")
                val command = arrayOf("su", "-c", "pm", "install-write", "-S", "${apkFile.fileSize}", "$sessionId", apkFile.name)
                val process: Process = Runtime.getRuntime().exec(command)
                val inputPipe = apkFile.getInputStream()
                try {
                    process.outputStream.use { outputStream -> inputPipe.copyTo(outputStream) }
                } catch (e: Exception) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        process.destroyForcibly()
                    else
                        process.destroy()

                    throw RuntimeException(e)
                }
                process.waitFor()
            }
        }
        Log.d("AppLog", "committing...")
        val installResult = Shell.su("pm install-commit $sessionId").exec()
        if (installResult.isSuccess) {
            return true
        } else
            sendFailure(installResult.out, context)

        return false
    }

    private fun SimpleDateFormat.tryParse(str: String) = try {
        parse(str) != null
    } catch (e: Exception) {
        false
    }

    private fun getFileInfoList(splitApkPath: String): ArrayList<FileInfo> {
        val parentFile = File(splitApkPath)
        val result = ArrayList<FileInfo>()

        if (parentFile.exists() && parentFile.canRead()) {
            val listFiles = parentFile.listFiles() ?: return ArrayList()
            for (file in listFiles)
                result.add(FileInfo(file.name, file.length(), file))
            return result
        }
        val longLines = Shell.su("ls -l $splitApkPath").exec().out
        val pattern = Pattern.compile(" +")
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        longLinesLoop@ for (line in longLines) {
            val matcher = pattern.matcher(line)
            for (i in 0 until 4)
                if (!matcher.find())
                    continue@longLinesLoop
            val startSizeStr = matcher.end()
            matcher.find()
            val endSizeStr = matcher.start()
            val fileSizeStr = line.substring(startSizeStr, endSizeStr)
            while (true) {
                val testTimeStr: String =
                    line.substring(matcher.end(), line.indexOf(' ', matcher.end()))
                if (formatter.tryParse(testTimeStr)) {
                    //found time, so apk is next
                    val fileName = line.substring(line.indexOf(' ', matcher.end()) + 1)
                    if (fileName.endsWith("apk"))
                        result.add(FileInfo(fileName, fileSizeStr.toLong(), File(splitApkPath, fileName)))
                    break
                }
                matcher.find()
            }
        }
        return result
    }

    //install Vanced
    private fun overwriteBase(apkFile: FileInfo,baseApkFiles: ArrayList<FileInfo>, versionCode: Int, context: Context): Boolean {
        if (checkVersion(versionCode, baseApkFiles, context)) {
            val path = getPackageDir(context)
            apkFile.file?.let {
                val apath = it.absolutePath

                setupFolder(apkInstallPath)
                if (path != null) {
                    val apkFPath = apkInstallPath + "base.apk"
                    if (moveAPK(apath, apkFPath, context)) {
                        if (chConV(apkFPath, context)) {
                            if (setupScript(apkFPath,path)) {
                                return linkVanced(apkFPath,path)
                            }
                        }
                    }

                }

            }
        }
        return false
    }

    private fun setupScript(apkFPath: String, path: String): Boolean {
        if (Shell.su("""echo "#!/system/bin/sh\nmount -o bind $apkFPath $path" > /data/adb/service.d/vanced.sh""").exec().isSuccess) {
            return Shell.su("chmod 744 /data/adb/service.d/vanced.sh").exec().isSuccess
        }
        return false
    }

    private fun linkVanced(apkFPath: String, path: String): Boolean {
        Shell.su("am force-stop $vancedRootPkg").exec()
        val umountv = Shell.su("""for i in ${'$'}(ls /data/app/ | grep com.google.android.youtube | tr " "); do umount -l "/data/app/${"$"}i/base.apk"; done """).exec()
        //Log.d("umountTest", Shell.su("grep com.google.android.youtube").exec().out.joinToString(" "))
        val response = Shell.su("""su -mm -c "mount -o bind $apkFPath $path"""").exec()
        Thread.sleep(500)
        Shell.su("am force-stop $vancedRootPkg").exec()
        return response.isSuccess
    }

    private fun setupFolder(apkInstallPath: String): Boolean {
        return Shell.su("mkdir -p $apkInstallPath").exec().isSuccess
    }

    //check version and perform action based on result
    private fun checkVersion(versionCode: Int, baseApkFiles: ArrayList<FileInfo>, context: Context): Boolean {
        val path = getPackageDir(context)
        if (path != null) {
            if (path.contains("/data/app/")) {
                when (getVersionNumber(context)?.let { compareVersion(it,versionCode) } ) {
                    1 -> return fixHigherVer(baseApkFiles, context)
                    -1 -> return fixLowerVer(baseApkFiles, context)
                }
                return true
            }
            else {
                return fixNoInstall(baseApkFiles, context)
            }
        }
        return fixNoInstall(baseApkFiles, context)
    }

    private fun getPkgInfo(pkg: String, context: Context): PackageInfo? {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
        } catch (e:Exception) {
            Log.d("VMpm", "Unable to get package info")
            null
        }
    }

    private fun compareVersion(pkgVerCode: Int, versionCode: Int): Int {
        return when {
            pkgVerCode > versionCode -> 1
            pkgVerCode < versionCode -> -1
            else -> 0
        }
    }

    //uninstall current update and install base that works with patch
    private fun fixHigherVer(apkFiles: ArrayList<FileInfo>, context: Context) : Boolean {
        if (uninstallApk(vancedRootPkg, context)) {
            return installSplitApkFiles(apkFiles, context)
        }
        sendFailure(listOf("Failed_Uninstall").toMutableList(), context)
        return false
    }

    //install newer stock youtube
    private fun fixLowerVer(apkFiles: ArrayList<FileInfo>, context: Context): Boolean {
        return installSplitApkFiles(apkFiles, context)
    }

    //install stock youtube since no install was found
    private fun fixNoInstall(baseApkFiles: ArrayList<FileInfo>, context: Context): Boolean {
        return installSplitApkFiles(baseApkFiles, context)
    }

    //set chcon to apk_data_file
    private fun chConV(path: String, context: Context): Boolean {
        val response = Shell.su("chcon u:object_r:apk_data_file:s0 $path").exec()
        //val response = Shell.su("chcon -R u:object_r:system_file:s0 $path").exec()
        return if (response.isSuccess) {
            true
        } else {
            sendFailure(response.out, context)
            false
        }
    }

    //move patch to data/app
    private fun moveAPK(apkFile: String, path: String, context: Context) : Boolean {
        val apkinF = SuFile.open(apkFile)
        val apkoutF = SuFile.open(path)

        if(apkinF.exists()) {
            try {
                Shell.su("am force-stop $vancedRootPkg").exec()

                //Shell.su("rm -r SuFile.open(path).parent")

                copy(apkinF,apkoutF)
                Shell.su("chmod 644 $path").exec().isSuccess
                return if(Shell.su("chown system:system $path").exec().isSuccess) {
                    true
                } else {
                    sendFailure(listOf("Chown_Fail").toMutableList(), context)
                    false
                }

            }
            catch (e: IOException)
            {
                sendFailure(listOf("${e.message}").toMutableList(), context)
                return false
            }
        }
        else {
            sendFailure(listOf("IFile_Missing").toMutableList(), context)
            return false
        }
    }


    @Throws(IOException::class)
    fun copy(src: File?, dst: File?) {
        val cmd = Shell.su("mv ${src!!.absolutePath} ${dst!!.absolutePath}").exec().isSuccess
        Log.d("ZLog", cmd.toString())
    }

    @Suppress("DEPRECATION")
    private fun getVersionNumber(context: Context): Int? {
        try {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                context.packageManager.getPackageInfo(vancedRootPkg, 0).longVersionCode.and(0xFFFFFFFF).toInt()
            else
                context.packageManager.getPackageInfo(vancedRootPkg, 0).versionCode
        }
        catch (e : Exception) {
            val execRes = Shell.su("dumpsys package com.google.android.youtube | grep versionCode").exec()
            if(execRes.isSuccess) {
                val result = execRes.out
                var version = 0
                for(line in result) {
                    val versionCode = line.substringAfter("=")
                    val versionCodeFiltered = versionCode.substringBefore(" ")
                    if(version < Integer.valueOf(versionCodeFiltered))
                    {
                        version = Integer.valueOf(versionCodeFiltered)
                    }
                }
                return version
            }
        }
        return null
    }

    //get path of the installed youtube
    private fun getPackageDir(context: Context): String?
    {
        return try {
            val p = getPkgInfo(vancedRootPkg, context)
            p!!.applicationInfo.sourceDir
        } catch (e: Exception) {
             val execRes = Shell.su("dumpsys package com.google.android.youtube | grep codePath").exec()
            if(execRes.isSuccess)
            {
                val result = execRes.out
                for (line in result)
                {
                    if(line.contains("data/app")) "${line.substringAfter("=")}/base.apk"
                }
            }
            null
        }
    }
}
