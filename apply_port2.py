import re
from pathlib import Path

REPO = Path(r'C:\Users\cap\Desktop\openintel-26.2-work')

# Yarn -> Mojmap member names, verified by the earlier port commit diff.
RENAME = {
    'currentScreen': 'screen',
    'textRenderer': 'font',
    'inGameHud': 'gui',
    'setPlaceholder': 'setHint',
    'setChangedListener': 'setResponder',
    'addDrawableChild': 'addRenderableWidget',
    'dimensions': 'bounds',
    'getText': 'getValue',
    'setText': 'setValue',
    'getPlayers': 'players',
    'sendMessage': 'sendSystemMessage',
    'renderInGameBackground': 'extractTransparentBackground',
    'renderBackground': 'extractBackground',
    'isPressed': 'isDown',
    'wasPressed': 'consumeClick',
    'getScaledWindowWidth': 'guiWidth',
    'getScaledWindowHeight': 'guiHeight',
    'getMatrices': 'pose',
    'drawText': 'text',
    'drawTextWithShadow': 'text',
    'drawCenteredTextWithShadow': 'centeredText',
    'drawTexture': 'blit',
    'drawItem': 'item',
    'drawItemWithoutEntity': 'fakeItem',
    'drawStrokedRectangle': 'outline',
    'drawTooltip': 'setComponentTooltipForNextFrame',
    'emptyTooltip': 'noTooltip',
    'getUuid': 'getUUID',
    'getUsername': 'getName',
    'getSession': 'getUser',
    'getNetworkHandler': 'getConnection',
    'getCurrentServerEntry': 'getCurrentServer',
    'isCursorLocked': 'isMouseGrabbed',
    'isInSingleplayer': 'hasSingleplayerServer',
    'isOnThread': 'isSameThread',
    'shouldPause': 'isPauseScreen',
    'hudHidden': 'hideGui',
    'isKeyPressed': 'isKeyDown',
    'matchesKey': 'matches',
    'fromKeyCode': 'getKey',
    'fromTranslationKey': 'getKey',
    'getKeycode': 'key',
    'timesPressed': 'clickCount',
    'tickProgress': 'partialTick',
    'getRenderTickCounter': 'getDeltaTracker',
    'getTickProgress': 'getGameTimeDeltaPartialTick',
    'getStatusEffects': 'getActiveEffects',
    'getHungerManager': 'getFoodData',
    'isInfinite': 'isInfiniteDuration',
    'getEffectType': 'getEffect',
    'getDamage': 'getDamageValue',
    'isDamageable': 'isDamageableItem',
    'getItemBarColor': 'getBarColor',
    'getItemBarStep': 'getBarWidth',
    'getEquippedStack': 'getItemBySlot',
    'getMainHandStack': 'getMainHandItem',
    'getStack': 'getItem',
    'getPickBlockStack': 'getPickResult',
    'getPlayerListEntry': 'getPlayerInfo',
    'getSkinTextures': 'getSkin',
    'getEyePos': 'getEyePosition',
    'getEntityPos': 'position',
    'getCameraPos': 'position',
    'getLerpedPos': 'getPosition',
    'getEntities': 'entitiesForRendering',
    'getCamera': 'getMainCamera',
    'getPitch': 'getXRot',
    'getYaw': 'getYRot',
    'setPitch': 'setXRot',
    'setYaw': 'setYRot',
    'hasVehicle': 'isPassenger',
    'setBoundKey': 'setKey',
    'setPressed': 'setDown',
    'world': 'level',          # Minecraft.level field
    'attackKey': 'keyAttack',
    'forwardKey': 'keyUp',
    'jumpKey': 'keyJump',
    'sprintKey': 'keySprint',
    'useKey': 'keyUse',
    'hotbarKeys': 'keyHotbarSlots',
    'updateKeysByCode': 'resetMapping',
    'registerKeyBinding': 'registerKeyMapping',
    'getBoundKeyLocalizedText': 'getTranslatedKeyMessage',
    'getBoundKeyTranslationKey': 'saveString',
    'createNewRootLayer': 'nextStratum',
    'applyBlur': 'blurBeforeThisStratum',
    'addSimpleElement': 'addGuiElement',
    'setupVertices': 'buildVertices',
    'transformEachVertex': 'transformMaxBounds',
    'createWidget': 'createButton',
    'createFromCode': 'getOrCreate',
    'registerTexture': 'register',
    'destroyTexture': 'release',
    'ofBoolean': 'createBoolean',
    'ofCenter': 'atCenterOf',
    'ofFloored': 'containing',
    'emptyTooltip': 'noTooltip',
}

# 'world' is risky as a bare token: only rewrite as member access .world
tok_most = {k: v for k, v in RENAME.items() if k != 'world'}
tok_re = re.compile(r'\b(' + '|'.join(re.escape(k) for k in sorted(tok_most, key=len, reverse=True)) + r')\b')

changed = []
for f in (REPO / 'mod/src').rglob('*.java'):
    src = f.read_text(encoding='utf-8')
    out = tok_re.sub(lambda m: tok_most[m.group(1)], src)
    out = re.sub(r'\b(client|minecraft|mc)\.world\b', r'\1.level', out)
    if out != src:
        f.write_text(out, encoding='utf-8', newline='\n')
        changed.append(f.name)
print(len(changed), 'files updated')
