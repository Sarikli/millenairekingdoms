"""Normal production-JAR lifecycle; no GameTest mod, graphical or authentication claim."""
from pathlib import Path
import subprocess, time, re, json, hashlib

project = Path('/workspace/millenairekingdoms')
version = '1.2.0-rc8-alpha.3'
world = project / 'run/royal-rc8-alpha3-ocean-disabled-01'
world.mkdir(exist_ok=True)
output_dir = Path('/workspace/royal-decree/ocean-loading')
jar = project / ('build/libs/millenairekingdoms-' + version + '.jar')
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def properties(path):
    return sorted(line.strip() for line in path.read_text().splitlines()
                  if line.strip() and not line.lstrip().startswith(('#', '!')))
for name in ['eula.txt', 'server.properties']:
    target = world / name
    if not target.exists():
        target.write_bytes((project / 'run/royal903-server-smoke' / name).read_bytes())
before_properties = properties(world / 'server.properties')
before_eula = (world / 'eula.txt').read_bytes()
results = []
for phase in ['write', 'reopen']:
    log = output_dir / ('alpha3-normal-server-' + phase + '.log')
    before_level = sha(world / 'world/level.dat') if (world / 'world/level.dat').exists() else None
    assert (before_level is None) == (phase == 'write'), 'Fresh write, SAME existing world on reopen'
    if results:
        assert before_level == results[-1]['level_after_sha256'], 'Reopen begins with the exact acknowledged saved level.dat'
    with log.open('w') as output:
        process = subprocess.Popen([
            'python', '/workspace/cloud-setup/millenaire-gradle.py', '--offline',
            '--init-script', '/workspace/royal-decree/rc-smoke.gradle',
            '-Dkingdoms.smokeDirectory=' + str(world), 'runServerJar'
        ], cwd=project, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True)
        deadline = time.monotonic() + 240
        ready = False
        while process.poll() is None and time.monotonic() < deadline:
            if re.search(r'Done \([^)]+\)!', log.read_text(errors='replace')):
                ready = True
                process.stdin.write('kingdoms list\nsave-all flush\nstop\n')
                process.stdin.flush()
                break
            time.sleep(1)
        if not ready:
            process.terminate()
            process.wait(timeout=30)
            raise SystemExit('Normal server did not become ready: ' + str(log))
        try:
            code = process.wait(timeout=120)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=30)
            raise SystemExit('Normal server did not stop cleanly: ' + str(log))
    text = log.read_text(errors='replace')
    staged = world / 'mods/millenairekingdoms.jar'
    row = dict(
        phase=phase, exit_code=code, ready=ready,
        saved='Saved the game' in text, stopped='Stopping server' in text,
        build_successful='BUILD SUCCESSFUL' in text,
        final_initialized=('Millénaire Kingdoms ' + version + ' initialized') in text,
        dependency_verified='dependency verified: millenaire 9.0.3' in text,
        test_mod_loaded='millenairekingdoms_tests' in text,
        jar_sha256=sha(jar), staged_jar_sha256=sha(staged), log_sha256=sha(log),
        level_before_sha256=before_level, level_after_sha256=sha(world / 'world/level.dat'),
        same_world=str(world), properties_semantically_unchanged=properties(world / 'server.properties') == before_properties,
        eula_unchanged=(world / 'eula.txt').read_bytes() == before_eula,
        authentication='Unverified: existing loopback offline-mode fixture, 127.0.0.1:25579.'
    )
    assert code == 0 and all(row[k] for k in [
        'ready', 'saved', 'stopped', 'build_successful', 'final_initialized',
        'dependency_verified', 'properties_semantically_unchanged', 'eula_unchanged'
    ]) and not row['test_mod_loaded'] and staged.read_bytes() == jar.read_bytes(), row
    results.append(row)
    (output_dir / 'alpha3-normal-server-results.json').write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(row), flush=True)
