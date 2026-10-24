import de.evp.helfer.SettingsTransfer;
import java.io.*;
import java.util.Map;
public class TestSettingsTransfer {
    public static void main(String[] args) throws Exception {
        String text=SettingsTransfer.encode("de","dark",false);
        Map<String,String> result=SettingsTransfer.decode(text);
        if(result.size()!=3 || !"de".equals(result.get("lang")) || !"false".equals(result.get("boot")))
            throw new AssertionError("Round trip failed");
        for(String extra:new String[]{"key=secret","paired=true","theme=invalid","format=other"}) {
            try { SettingsTransfer.decode(text+"\n"+extra); throw new AssertionError("Unsafe input accepted"); }
            catch(IOException expected) {}
        }
        try { SettingsTransfer.read(new ByteArrayInputStream(new byte[8193])); throw new AssertionError("Oversize accepted"); }
        catch(IOException expected) {}
        if(text.contains("paired=")||text.contains("key=")) throw new AssertionError("Credentials exported");
        System.out.println("PASS: settings round trip, allowlist, secret exclusion and size limit");
    }
}
