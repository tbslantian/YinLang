"""Generate a dependency-free Xcode project deterministically on Windows/macOS."""
from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / 'YinLang.xcodeproj'
PROJECT.mkdir(exist_ok=True)
objects = {}

def uid(name):
    return hashlib.sha256(name.encode()).hexdigest()[:24].upper()

def obj(name, value):
    key = uid(name)
    objects[key] = value
    return key

def array(values):
    return '(' + ', '.join(values) + ', )' if values else '()'

def quoted(value):
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'

def file(path, kind):
    return obj(path, f'{{isa = PBXFileReference; lastKnownFileType = {kind}; path = {quoted(path)}; sourceTree = SOURCE_ROOT; }}')

def phase(name, isa, files):
    entries = []
    for item in files:
        entries.append(obj(name + item, f'{{isa = PBXBuildFile; fileRef = {item}; }}'))
    return obj(name, f'{{isa = {isa}; buildActionMask = 2147483647; files = {array(entries)}; runOnlyForDeploymentPostprocessing = 0; }}')

def configs(name, settings):
    ids = []
    for config in ['Debug', 'Release']:
        config_settings = settings | ({'SWIFT_ACTIVE_COMPILATION_CONDITIONS': 'DEBUG', 'SWIFT_OPTIMIZATION_LEVEL': '-Onone', 'GCC_OPTIMIZATION_LEVEL': '0', 'ENABLE_TESTABILITY': 'YES'} if config == 'Debug' else {'SWIFT_OPTIMIZATION_LEVEL': '-O', 'SWIFT_COMPILATION_MODE': 'wholemodule'})
        body = ' '.join(f'{key} = {quoted(str(value))};' for key, value in config_settings.items())
        ids.append(obj(name + config, f'{{isa = XCBuildConfiguration; buildSettings = {{{body}}}; name = {config}; }}'))
    return obj(name + 'configs', f'{{isa = XCConfigurationList; buildConfigurations = {array(ids)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }}')

app_sources = [file(str(path.relative_to(ROOT)).replace('\\', '/'), {'.swift':'sourcecode.swift','.c':'sourcecode.c.c','.m':'sourcecode.c.objc'}[path.suffix]) for path in sorted((ROOT / 'YinLang').rglob('*')) if path.suffix in ['.swift','.c','.m']]
unit_sources = [file(str(path.relative_to(ROOT)).replace('\\', '/'), 'sourcecode.swift') for path in sorted((ROOT / 'YinLangTests').glob('*.swift'))]
ui_sources = [file(str(path.relative_to(ROOT)).replace('\\', '/'), 'sourcecode.swift') for path in sorted((ROOT / 'YinLangUITests').glob('*.swift'))]
assets = file('YinLang/Resources/Assets.xcassets', 'folder.assetcatalog')
runtime = file('YinLang/Resources/Runtime', 'folder')
plist = file('YinLang/Info.plist', 'text.plist.xml')
header = file('YinLang/Runtime/Bridge.h', 'sourcecode.c.h')
products = []
for name, ext, kind in [('YinLang', 'app', 'wrapper.application'), ('YinLangTests', 'xctest', 'wrapper.cfbundle'), ('YinLangUITests', 'xctest', 'wrapper.cfbundle')]:
    products.append(obj(name + 'product', f'{{isa = PBXFileReference; explicitFileType = {kind}; path = {name}.{ext}; sourceTree = BUILT_PRODUCTS_DIR; }}'))
product_group = obj('products', f'{{isa = PBXGroup; children = {array(products)}; name = Products; sourceTree = "<group>"; }}')
group = obj('mainGroup', f'{{isa = PBXGroup; children = {array(app_sources + unit_sources + ui_sources + [assets, runtime, plist, header, product_group])}; sourceTree = "<group>"; }}')
common = {'IPHONEOS_DEPLOYMENT_TARGET':'17.0', 'SWIFT_VERSION':'5.0', 'TARGETED_DEVICE_FAMILY':'1,2', 'SDKROOT':'iphoneos', 'CODE_SIGN_STYLE':'Automatic', 'CLANG_ENABLE_MODULES':'YES', 'SWIFT_EMIT_LOC_STRINGS':'YES', 'SUPPORTED_PLATFORMS':'iphoneos iphonesimulator', 'SUPPORTS_MACCATALYST':'NO'}
app_config = configs('app', common | {'PRODUCT_BUNDLE_IDENTIFIER':'app.yinlang.ios', 'PRODUCT_NAME':'YinLang', 'INFOPLIST_FILE':'YinLang/Info.plist', 'SWIFT_OBJC_BRIDGING_HEADER':'YinLang/Runtime/Bridge.h', 'OTHER_LDFLAGS':'$(inherited) -lz', 'MARKETING_VERSION':'0.8.15', 'CURRENT_PROJECT_VERSION':'18', 'LD_RUNPATH_SEARCH_PATHS':'$(inherited) @executable_path/Frameworks', 'ASSETCATALOG_COMPILER_APPICON_NAME':'AppIcon'})
app_phases = [phase('appSources','PBXSourcesBuildPhase',app_sources), phase('appFrameworks','PBXFrameworksBuildPhase',[]), phase('appResources','PBXResourcesBuildPhase',[assets,runtime])]
app_target = obj('appTarget', f'{{isa = PBXNativeTarget; buildConfigurationList = {app_config}; buildPhases = {array(app_phases)}; buildRules = (); dependencies = (); name = YinLang; productName = YinLang; productReference = {products[0]}; productType = "com.apple.product-type.application"; }}')
targets = [app_target]
for name, files, product, ui in [('YinLangTests', unit_sources, products[1], False), ('YinLangUITests', ui_sources, products[2], True)]:
    proxy = obj(name + 'proxy', f'{{isa = PBXContainerItemProxy; containerPortal = {uid("project")}; proxyType = 1; remoteGlobalIDString = {app_target}; remoteInfo = YinLang; }}')
    dependency = obj(name + 'dependency', f'{{isa = PBXTargetDependency; target = {app_target}; targetProxy = {proxy}; }}')
    settings = common | {'PRODUCT_BUNDLE_IDENTIFIER':'app.yinlang.' + name.lower(), 'PRODUCT_NAME':name, 'GENERATE_INFOPLIST_FILE':'YES'}
    settings |= {'TEST_TARGET_NAME':'YinLang'} if ui else {'TEST_HOST':'$(BUILT_PRODUCTS_DIR)/YinLang.app/YinLang', 'BUNDLE_LOADER':'$(TEST_HOST)'}
    conf = configs(name, settings)
    phases = [phase(name + 'Sources','PBXSourcesBuildPhase',files), phase(name + 'Frameworks','PBXFrameworksBuildPhase',[])]
    targets.append(obj(name + 'target', f'{{isa = PBXNativeTarget; buildConfigurationList = {conf}; buildPhases = {array(phases)}; buildRules = (); dependencies = ({dependency}, ); name = {name}; productName = {name}; productReference = {product}; productType = "com.apple.product-type.bundle.{"ui-testing" if ui else "unit-test"}"; }}'))
project_config = configs('project', {'CLANG_ENABLE_MODULES':'YES', 'SWIFT_VERSION':'5.0'})
project = obj('project', f'{{isa = PBXProject; attributes = {{BuildIndependentTargetsInParallel = YES; LastUpgradeCheck = 1640; }}; buildConfigurationList = {project_config}; compatibilityVersion = "Xcode 14.0"; developmentRegion = "zh-Hans"; hasScannedForEncodings = 0; knownRegions = ("zh-Hans", en, Base); mainGroup = {group}; productRefGroup = {product_group}; projectDirPath = ""; projectRoot = ""; targets = {array(targets)}; }}')
body = '\n'.join(key + ' = ' + value + ';' for key, value in objects.items())
(PROJECT / 'project.pbxproj').write_text('// !$*UTF8*$!\n{archiveVersion = 1; classes = {}; objectVersion = 56; objects = {\n' + body + '\n}; rootObject = ' + project + '; }\n', encoding='utf-8')
scheme_dir = PROJECT / 'xcshareddata/xcschemes'
scheme_dir.mkdir(parents=True, exist_ok=True)
def reference(target, name, filename):
    return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="{filename}" BlueprintName="{name}" ReferencedContainer="container:YinLang.xcodeproj"/>'
app_ref = reference(app_target, 'YinLang', 'YinLang.app')
test_refs = ''.join('<TestableReference skipped="NO">' + reference(target, name, name + '.xctest') + '</TestableReference>' for target, name in zip(targets[1:], ['YinLangTests', 'YinLangUITests']))
scheme = f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1640" version="1.7">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{app_ref}</BuildActionEntry></BuildActionEntries></BuildAction>
<TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables>{test_refs}</Testables></TestAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{app_ref}</BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{app_ref}</BuildableProductRunnable></ProfileAction>
<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>'''
(scheme_dir / 'YinLang.xcscheme').write_text(scheme, encoding='utf-8')
print(f'Generated {PROJECT} with {len(app_sources)} app source files.')
