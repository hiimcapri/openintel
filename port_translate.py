import re, subprocess, collections
from pathlib import Path

REPO = Path(r'C:\Users\cap\Desktop\openintel-26.2-work')

diff = subprocess.check_output(
    ['git', 'diff', '-U0', 'fda2e0f~1', 'fda2e0f', '--', 'mod/src/main/java'],
    cwd=REPO, text=True, encoding='utf-8', errors='replace')

pairs = []
cm, cp = [], []
def flush():
    if len(cm) == len(cp):
        pairs.extend(zip(cm, cp))
    cm.clear(); cp.clear()
for line in diff.splitlines():
    if line.startswith('@@'):
        flush(); continue
    if line.startswith('---') or line.startswith('+++'):
        continue
    if line.startswith('-'):
        cm.append(line[1:])
    elif line.startswith('+'):
        cp.append(line[1:])
    else:
        flush()
flush()

# 1) import map: full FQN pairs
imports = {}
for a, b in pairs:
    ia = re.match(r'\s*import\s+(static\s+)?([\w.]+);', a)
    ib = re.match(r'\s*import\s+(static\s+)?([\w.]+);', b)
    if ia and ib and ia.group(1) == ib.group(1):
        imports[ia.group(2)] = ib.group(2)

# 2) identifier map: token pairs on non-import changed lines
tok = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')
votes = collections.defaultdict(collections.Counter)
for a, b in pairs:
    if re.match(r'\s*import\s', a):
        continue
    ta, tb = tok.findall(a), tok.findall(b)
    if len(ta) != len(tb):
        continue
    for x, y in zip(ta, tb):
        if x != y:
            votes[x][y] += 1

ident = {}
for x, c in votes.items():
    y, n = c.most_common(1)[0]
    # keep only consistent renames
    if sum(c.values()) == n:
        ident[x] = y

# safety filter: drop ambiguous lowercase sources
AMBIG = {'of','type','close','world','entity','component','address','name','state','layer',
         'render','create','color','draw','gl','gui','keybinding','math','minecraft','mouse',
         'net','registry','screen','sound','texture','util','widget','write','client','keyinput',
         'getValue','getText','getName','getId','getKeycode','getWidth','network','project',
         'text','hit','empty','getKey','getRotation','getHandle','getImage','getCategory',
         'getStack','getItemBarColor','getItemBarStep','getSession','formatted','styled',
         'dimensions','fontHeight','displayName','forwardKey','attackKey','jumpKey','sprintKey',
         'useKey','hotbarKeys','hudHidden','isPressed','tickProgress','timesPressed','type',
         'fromKeyCode','fromTranslationKey','getEffectType','getDamage','getEquippedStack',
         'getMainHandStack','getPlayers','getEntityPos','getCameraPos','getCamera',
         'getLerpedPos','getPitch','getYaw','setPitch','setYaw','getRegistryKey',
         'getCurrentServerEntry','getStatusEffects','getHungerManager','getPlayerListEntry',
         'getUsername','getUuid','getRenderTickCounter','getTickProgress','getScaledWindowHeight',
         'getScaledWindowWidth','isCursorLocked','isKeyPressed','isInSingleplayer','isOnThread',
         'isDamageable','isInfinite','hasVehicle','shouldPause','wasPressed','matchesKey',
         'sendMessage','setText','setPlaceholder','setBoundKey','setPressed','setChangedListener',
         'setColorArgb','setupVertices','transformEachVertex','createWidget','createNewRootLayer',
         'applyBlur','addDrawableChild','addSimpleElement','updateKeysByCode','registerKeyBinding',
         'registerTexture','destroyTexture','ofBoolean','ofCenter','ofFloored','of','onClose',
         'getBoundKeyLocalizedText','getBoundKeyTranslationKey','getNetworkHandler',
         'getPickBlockStack','getEyePos','getEntities','drawTexture','drawTooltip',
         'drawCenteredTextWithShadow','drawTextWithShadow','drawText','drawItem',
         'drawItemWithoutEntity','drawStrokedRectangle','draw','emptyTooltip','raycast',
         'renderBackground','renderInGameBackground','keybinding','BLOCK_NOTE_BLOCK_PLING'}
# NOTE: AMBIG here lists what we KEEP-DROP decision below; lowercase/short sources are dropped
drop = set()
for x in list(ident):
    if x[0].islower() and len(x) < 8:
        drop.add(x)
# always keep UpperCamel class names and ALL_CAPS constants
safe = {x: y for x, y in ident.items() if x not in drop}
print('imports:', len(imports))
for k in sorted(imports): print('  I', k, '->', imports[k])
print('idents:', len(safe))
for k in sorted(safe): print('  T', k, '->', safe[k])

# persist for reuse
import json
Path('port_map.json').write_text(json.dumps({'imports': imports, 'idents': safe}, indent=1))
