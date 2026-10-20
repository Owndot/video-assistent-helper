package de.evp.helfer;
public class TestCookie {
  public static void main(String[] a) {
    Fetcher.Http h = new Fetcher.Http("https://www.example.test/watch/1", "UA", "sess=abc");
    String[][] c = { {"https://www.example.test/v.mp4","true"}, {"https://cdn.example.test/v.mp4","true"}, {"https://example.test/v.mp4","true"},
                     {"https://bank.com/konto","false"}, {"https://example.test.evil.com/x","false"}, {"https://notexample.test/x","false"} };
    int ok = 0;
    for (String[] x : c) { boolean got = h.cookieFor(x[0]); boolean want = Boolean.parseBoolean(x[1]); System.out.println((got == want ? "OK   " : "FAIL ") + x[0] + " → Cookie " + (got ? "ja" : "nein")); if (got == want) ok++; }
    Fetcher.Http uk = new Fetcher.Http("https://www.bbc.co.uk/x", "UA", "s=1");
    boolean a1 = uk.cookieFor("https://media.bbc.co.uk/v"), a2 = !uk.cookieFor("https://other.co.uk/v");
    System.out.println((a1 && a2 ? "OK   " : "FAIL ") + "co.uk: eigene Server ja, fremde .co.uk nein"); if (a1 && a2) ok++;
    System.out.println("ERGEBNIS: " + ok + " von " + (c.length + 1));
  }
}
