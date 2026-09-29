import json, re
from pathlib import Path

REPO = Path(r'C:\Users\cap\Desktop\openintel-26.2-work')
maps = json.loads(Path('port_map.json').read_text())
imports, idents = maps['imports'], maps['idents']

# Contextual renames dropped: correct for one receiver type, wrong/ambiguous for others.
DROP = {'of','type','close','world','entity','component','address','name','state',
        'layer','render','create','color','draw','gl','gui','keybinding','math',
        'minecraft','mouse','net','registry','screen','sound','texture','util',
        'widget','write','client','keyinput','getValue','getText','getName','getId',
        'getWidth','network','project','text','hit','empty','getKey','getRotation',
        'getHandle','getImage','getCategory','getStack','currentScreen','dimensions',
        'displayName','fontHeight','tickProgress','timesPressed','formatted','styled',
        'getPlayers','getEntities','getItemBarColor','getItemBarStep','emptyTooltip',
        'PotentialValuesBasedCallbacks','ValidatingIntSliderCallbacks','getNetworkHandler',
        'getSession','createFromCode','createWidget','createNewRootLayer',
        'transformEachVertex','setupVertices','raycast','render','getLerpedPos',
        'getEntityPos','getCameraPos','getEyePos','getPickBlockStack',
        'getStatusEffects','getHungerManager','getPlayerListEntry','isInfinite',
        'isDamageable','hasVehicle','shouldPause','matchesKey','fromKeyCode',
        'fromTranslationKey','getEffectType','getDamage','getEquippedStack',
        'getMainHandStack','getRegistryKey','setText','setPitch','setYaw','getPitch',
        'getYaw','getKeycode','ofBoolean','ofCenter','ofFloored','isOnThread',
        'isKeyPressed','isCursorLocked','isInSingleplayer','hudHidden','hotbarKeys',
        'attackKey','forwardKey','jumpKey','sprintKey','useKey','textRenderer',
        'getScaledWindowWidth','getScaledWindowHeight','setColorArgb','setBoundKey',
        'setPressed','setPlaceholder','setChangedListener','updateKeysByCode',
        'registerKeyBinding','registerTexture','destroyTexture','getBoundKeyLocalizedText',
        'getBoundKeyTranslationKey','getCurrentServerEntry','getUsername','getUuid',
        'getRenderTickCounter','getTickProgress','getSkinTextures','applyBlur',
        'addDrawableChild','addSimpleElement','renderBackground','renderInGameBackground',
        'drawTooltip','drawStrokedRectangle','drawItem','drawItemWithoutEntity',
        'drawCenteredTextWithShadow','drawTextWithShadow','drawText','drawTexture',
        'sendMessage','empty','hit','isPressed','wasPressed','getWidth','getKey',
        'PotentialValuesBasedCallbacks','ValidatingIntSliderCallbacks','WORLD','DIMENSION'}

# lower-only ambiguous handled above; WORLD/DIMENSION are field names on RegistryKeys/Registries
for bad in DROP:
    idents.pop(bad, None)

tok = re.compile(r'\b(' + '|'.join(re.escape(k) for k in sorted(idents, key=len, reverse=True)) + r')\b') if idents else None

changed = []
for f in list((REPO / 'mod/src').rglob('*.java')):
    src = f.read_text(encoding='utf-8')
    out = src
    # imports: exact FQN replace
    def rep(m):
        pref, fqn = m.group(1), m.group(2)
        return m.group(0).replace(fqn, imports.get(fqn, fqn))
    out = re.sub(r'import\s+(static\s+)?([\w.]+);', rep, out)
    if tok:
        out = tok.sub(lambda m: idents[m.group(1)], out)
    if out != src:
        f.write_text(out, encoding='utf-8', newline='\n')
        changed.append(str(f.relative_to(REPO)))

print(f'{len(changed)} files updated')
for c in changed:
    print(' ', c)
