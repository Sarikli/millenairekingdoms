import org.apache.maven.artifact.versioning.ComparableVersion;
public class VersionOrder {
    public static void main(String[] args) {
        var old = new ComparableVersion("1.2.0-rc8-alpha");
        var next = new ComparableVersion("1.2.0-rc8-alpha.2");
        var stable = new ComparableVersion("1.2.0");
        if (!(old.compareTo(next) < 0 && next.compareTo(stable) < 0)) throw new AssertionError("Invalid prerelease ordering");
        System.out.println("Maven ComparableVersion: 1.2.0-rc8-alpha < 1.2.0-rc8-alpha.2 < 1.2.0; actual NeoForge loader acceptance is checked in the normal-JAR lifecycle.");
    }
}
