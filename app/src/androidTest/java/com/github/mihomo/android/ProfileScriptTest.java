package com.github.mihomo.android;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import com.github.mihomo.android.data.ConfigScriptEngine;
import com.github.mihomo.android.data.ProfileParser;
import com.github.mihomo.android.data.ParsedProfile;
import com.github.mihomo.android.data.ScriptManager;
import com.github.mihomo.android.data.ScriptItem;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;

public class ProfileScriptTest {
    @Test
    public void testScriptProxyCaps() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String originalYaml = "Proxy:\n  - name: \"Node 1\"\n    type: ss\n    port: 443\nProxy Group:\n  - name: PROXY\n    type: select\n    proxies:\n      - \"Node 1\"\n";
        ScriptManager.INSTANCE.saveLocalScript(context, "test2", "", "profile-modify", "function main(config) { return config; }");
        ScriptItem script = ScriptManager.INSTANCE.getScripts(context).get(0);
        String modified = ConfigScriptEngine.INSTANCE.executeScripts(context, originalYaml, Collections.singletonList(script.getId()), false);
        File f = new File(context.getCacheDir(), "test2.yaml");
        Files.write(f.toPath(), modified.getBytes());
        ParsedProfile parsed = ProfileParser.INSTANCE.parse(f, new java.util.HashMap<>(), null);
        
        System.out.println("Modified YAML:\n" + modified);
        System.out.println("Parsed nodes: " + parsed.getTotalNodes());
        System.out.println("Parsed groups: " + parsed.getGroupNames());
        
        if (parsed.getTotalNodes() != 1) {
            throw new Exception("Nodes are missing! Expected 1, got " + parsed.getTotalNodes());
        }
    }
}
