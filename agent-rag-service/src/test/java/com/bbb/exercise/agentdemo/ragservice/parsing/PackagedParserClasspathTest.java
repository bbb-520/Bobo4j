package com.bbb.exercise.agentdemo.ragservice.parsing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.jar.*;
import static org.assertj.core.api.Assertions.*;
class PackagedParserClasspathTest {
    @TempDir Path directory;
    @Test void bootJarClasspathAppendsWildcardAsTextAfterResolvingDirectory() throws Exception {
        Path jar=directory.resolve("packaged.jar");
        try(var out=new JarOutputStream(Files.newOutputStream(jar))){
            for(String name:new String[]{"BOOT-INF/classes/","BOOT-INF/classes/parser.txt","BOOT-INF/lib/tika.jar"}){
                out.putNextEntry(new JarEntry(name));if(!name.endsWith("/"))out.write(new byte[]{1});out.closeEntry();
            }
        }
        Path work=directory.resolve("work");String cp=IsolatedTikaParser.unpackClasspath(work,jar.toString());
        assertThat(cp).endsWith("BOOT-INF"+java.io.File.separator+"lib"+java.io.File.separator+"*");
        assertThat(Files.exists(work.resolve("parser-runtime/BOOT-INF/lib/tika.jar"))).isTrue();
    }
}
