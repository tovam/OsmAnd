#!/usr/bin/env python3
"""Check imports of common Compose calls without Gradle, SDKs or an APK build.

This is a deliberately bounded source check, not Kotlin type resolution. It handles explicit,
wildcard and aliased imports, qualified calls and project declarations. Literal strings and
comments are ignored. The catalogue covers the common Compose APIs used by the flight UI.
"""

from pathlib import Path
import re


SOURCE_ROOT = Path(__file__).resolve().parents[2] / "src/net/osmand/plus/plugins/flightmode"
COMPONENTS = {
    "androidx.compose.material3": """
        AlertDialog Badge BadgedBox Button Card Checkbox CircularProgressIndicator DatePicker
        DatePickerDialog DropdownMenu DropdownMenuItem ElevatedButton ElevatedCard
        ExposedDropdownMenuBox FilledIconButton FilterChip FloatingActionButton Icon IconButton
        IconToggleButton LinearProgressIndicator ListItem ModalBottomSheet NavigationBar
        NavigationBarItem OutlinedButton OutlinedCard OutlinedTextField RadioButton RangeSlider
        Scaffold Slider Snackbar Surface Switch Tab TabRow Text TextButton TextField TooltipBox
        TopAppBar darkColorScheme lightColorScheme rememberModalBottomSheetState
    """.split(),
    "androidx.compose.foundation": "Canvas Image rememberScrollState".split(),
    "androidx.compose.foundation.layout": "Box BoxWithConstraints Column FlowColumn FlowRow Row Spacer".split(),
    "androidx.compose.foundation.lazy": "LazyColumn LazyRow rememberLazyListState".split(),
    "androidx.compose.foundation.text": "BasicText BasicTextField".split(),
    "androidx.compose.runtime": """
        CompositionLocalProvider DisposableEffect LaunchedEffect SideEffect derivedStateOf key
        mutableFloatStateOf mutableIntStateOf mutableLongStateOf mutableStateOf produceState
        remember rememberCoroutineScope rememberUpdatedState staticCompositionLocalOf
    """.split(),
    "androidx.compose.runtime.saveable": ["rememberSaveable"],
    "androidx.compose.ui.viewinterop": ["AndroidView"],
    "androidx.compose.ui.res": "colorResource dimensionResource painterResource pluralStringResource stringResource".split(),
}
PROVIDERS = {}
for package, symbols in COMPONENTS.items():
    for symbol in symbols:
        PROVIDERS.setdefault(symbol, set()).add(package)

IMPORT = re.compile(r"^\s*import\s+([\w.]+(?:\*)?)(?:\s+as\s+(\w+))?\s*$", re.MULTILINE)
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
CALL = re.compile(r"\b([A-Za-z_]\w*)\s*(?:\(|\{)")
DECLARATION = re.compile(r"\b(?:fun|class|object|typealias|val|var)\s+(?:<[^>\n]+>\s+)?([A-Za-z_]\w*)")


def mask_literals(source):
    """Preserve offsets/newlines, including after nested Kotlin block comments."""
    masked = list(source)
    i = 0
    while i < len(source):
        start = i
        if source.startswith("//", i):
            end = source.find("\n", i)
            i = len(source) if end < 0 else end
        elif source.startswith("/*", i):
            depth = 1
            i += 2
            while i < len(source) and depth:
                if source.startswith("/*", i):
                    depth += 1
                    i += 2
                elif source.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
        elif source.startswith('"""', i):
            end = source.find('"""', i + 3)
            i = len(source) if end < 0 else end + 3
        elif source[i] in ('"', "'"):
            quote = source[i]
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == quote:
                    i += 1
                    break
                else:
                    i += 1
        else:
            i += 1
            continue
        for at in range(start, min(i, len(source))):
            if masked[at] != "\n":
                masked[at] = " "
    return "".join(masked)


def missing_imports(source, package_declarations=()):
    code = mask_literals(source)
    imports = list(IMPORT.finditer(code))
    explicit = {alias or path.rsplit(".", 1)[-1] for path, alias in (m.groups() for m in imports) if not path.endswith(".*")}
    wildcards = {m.group(1)[:-2] for m in imports if m.group(1).endswith(".*")}
    declared = set(DECLARATION.findall(code)) | set(package_declarations)
    missing = []
    for call in CALL.finditer(code):
        symbol = call.group(1)
        if symbol not in PROVIDERS or symbol in explicit or symbol in declared:
            continue
        if PROVIDERS[symbol] & wildcards:
            continue
        # Fully qualified or receiver calls do not depend on an unqualified Compose import.
        previous = call.start() - 1
        while previous >= 0 and code[previous].isspace():
            previous -= 1
        if previous >= 0 and code[previous] == ".":
            continue
        line = source.count("\n", 0, call.start()) + 1
        missing.append((line, symbol))
    return missing


def check_sources(root=SOURCE_ROOT):
    sources = {path: path.read_text() for path in sorted(root.glob("*.kt"))}
    declarations = {}
    packages = {}
    for path, source in sources.items():
        code = mask_literals(source)
        match = PACKAGE.search(code)
        package = match.group(1) if match else ""
        packages[path] = package
        # Cross-file helpers must be top-level and visible within their package.
        names = re.findall(r"^(?:(?:public|internal|inline|suspend|tailrec)\s+)*fun\s+([A-Za-z_]\w*)\s*\(", code, re.MULTILINE)
        declarations.setdefault(package, set()).update(names)
    errors = []
    for path, source in sources.items():
        errors.extend((path, line, symbol) for line, symbol in missing_imports(source, declarations[packages[path]]))
    return sources, errors


if __name__ == "__main__":
    sources, errors = check_sources()
    for path, line, symbol in errors:
        print(f"{path}:{line}: missing Compose import for {symbol}")
    if errors:
        raise SystemExit(1)
    if not sources:
        raise SystemExit("No flight Kotlin sources found")
    print(f"Compose import checks passed for {len(sources)} flight Kotlin files.")
