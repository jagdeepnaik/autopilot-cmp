package com.jagdeep.autopilot

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Each file is moved before its store opens; existing destinations are never overwritten. */
internal class DesktopDataPaths(val root:File) {
    @Synchronized fun file(category:String,name:String):File {
        require(category.matches(Regex("[a-z/-]+")) && !category.contains(".."))
        require(name.matches(Regex("[a-zA-Z0-9_.-]+")) && !name.contains(".."))
        val folder=File(root,category)
        if(!folder.isDirectory && !folder.mkdirs()) throw IOException("Could not create application data folder")
        val target=File(folder,name)
        val names=listOf(name)+listOf("-wal","-shm",".lck",".tmp",".lock").map {name+it}
        if(names.any {File(root,it).exists() && File(folder,it).exists()}) throw IOException("Both old and new application data files exist; no data was overwritten")
        names.forEach {entry ->
            val old=File(root,entry);val next=File(folder,entry)
            if(old.exists()) Files.move(old.toPath(),next.toPath())
        }
        return target
    }
    @Synchronized fun chromeExtension():File {
        val target=File(root,"extensions/chrome");val previous=File(root,"chrome-extension")
        if(previous.exists()) {
            if(target.exists()) throw IOException("Two extension directories exist; neither was overwritten")
            target.parentFile.mkdirs();Files.move(previous.toPath(),target.toPath())
        }
        target.mkdirs();return target
    }
}
internal val desktopDataPaths by lazy {DesktopDataPaths(File(System.getProperty("user.home"),".autopilot"))}
