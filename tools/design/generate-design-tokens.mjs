#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const colorsTs = path.join(root, 'cinny', 'src', 'colors.css.ts');
const foldsTokensPath = path.join(root, 'tools', 'design', 'folds-2.7.2-tokens.json');
const outDir = path.join(root, 'core', 'src', 'top', 's0cl', 'cinny', 'core', 'design');
const checkMode = process.argv.includes('--check');

function loadCinnyThemes() {
  let source = fs.readFileSync(colorsTs, 'utf8');
  source = source.replace(/^import .*$/gm, '');
  source = source.replace(/export /g, '');
  const fn = new Function(
    'createTheme',
    'color',
    `${source}\nreturn { silverTheme, darkTheme, butterTheme, darkThemeData };`,
  );
  return fn((_, value) => value, {});
}

function partToAlpha(part) {
  if (part.endsWith('%')) return parseFloat(part) / 100;
  return undefined;
}

function parseColor(value) {
  if (typeof value !== 'string') throw new Error(`Unsupported color: ${value}`);
  if (value.startsWith('#')) {
    const hex = value.slice(1).toUpperCase();
    if (hex.length === 6) return `Color(0xFF${hex})`;
    if (hex.length === 8) return `Color(0x${hex})`;
    throw new Error(`Unsupported hex color: ${value}`);
  }
  const match = value.match(/rgba?\(([^)]+)\)/i);
  if (!match) throw new Error(`Unsupported color: ${value}`);
  const body = match[1].trim();
  let parts;
  if (body.includes('/')) {
    const [rgb, alpha] = body.split('/').map((part) => part.trim());
    parts = [...rgb.split(/\s+/), alpha];
  } else {
    parts = body.split(',').map((part) => part.trim());
  }
  if (parts.length === 3) parts.push('1');
  const [r, g, b, a] = parts.map((part) => parseFloat(part));
  const alpha = Math.round((partToAlpha(parts[3]) ?? a) * 255);
  const hex = (n) => Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, '0').toUpperCase();
  return `Color(0x${hex(alpha)}${hex(r)}${hex(g)}${hex(b)})`;
}

function joinLines(lines, indent) {
  return lines.map((line) => `${indent}${line}`).join('\n');
}

function containerLiteral(colors, indent = '    ') {
  return joinLines([
    `container = ${parseColor(colors.Container)},`,
    `containerHover = ${parseColor(colors.ContainerHover)},`,
    `containerActive = ${parseColor(colors.ContainerActive)},`,
    `containerLine = ${parseColor(colors.ContainerLine)},`,
    `onContainer = ${parseColor(colors.OnContainer)},`,
  ], indent);
}

function accentLiteral(colors, indent = '    ') {
  return joinLines([
    `main = ${parseColor(colors.Main)},`,
    `mainHover = ${parseColor(colors.MainHover)},`,
    `mainActive = ${parseColor(colors.MainActive)},`,
    `mainLine = ${parseColor(colors.MainLine)},`,
    `onMain = ${parseColor(colors.OnMain)},`,
    `container = ${parseColor(colors.Container)},`,
    `containerHover = ${parseColor(colors.ContainerHover)},`,
    `containerActive = ${parseColor(colors.ContainerActive)},`,
    `containerLine = ${parseColor(colors.ContainerLine)},`,
    `onContainer = ${parseColor(colors.OnContainer)},`,
  ], indent);
}

function otherLiteral(colors, indent = '    ') {
  return joinLines([
    `focusRing = ${parseColor(colors.FocusRing)},`,
    `shadow = ${parseColor(colors.Shadow)},`,
    `overlay = ${parseColor(colors.Overlay)},`,
  ], indent);
}

function themeLiteral(name, theme) {
  return [
    `    val ${name}: CinnyColors = CinnyColors(`,
    '        background = ContainerColors(',
    containerLiteral(theme.Background, '            '),
    '        ),',
    '        surface = ContainerColors(',
    containerLiteral(theme.Surface, '            '),
    '        ),',
    '        surfaceVariant = ContainerColors(',
    containerLiteral(theme.SurfaceVariant, '            '),
    '        ),',
    '        primary = AccentColors(',
    accentLiteral(theme.Primary, '            '),
    '        ),',
    '        secondary = AccentColors(',
    accentLiteral(theme.Secondary, '            '),
    '        ),',
    '        success = AccentColors(',
    accentLiteral(theme.Success, '            '),
    '        ),',
    '        warning = AccentColors(',
    accentLiteral(theme.Warning, '            '),
    '        ),',
    '        critical = AccentColors(',
    accentLiteral(theme.Critical, '            '),
    '        ),',
    '        other = OtherColors(',
    otherLiteral(theme.Other, '            '),
    '        ),',
    '    )',
  ].join('\n');
}

function generateColors() {
  const folds = JSON.parse(fs.readFileSync(foldsTokensPath, 'utf8'));
  const cinny = loadCinnyThemes();
  const themes = {
    Light: folds.lightTheme,
    Silver: cinny.silverTheme,
    Dark: cinny.darkTheme,
    Butter: cinny.butterTheme,
  };
  const themeBlocks = Object.entries(themes)
    .map(([name, theme]) => themeLiteral(name, theme))
    .join('\n\n');
  return `// GENERATED FILE. Do not edit by hand.
// Run: node tools/design/generate-design-tokens.mjs
//
// Sources:
//  - folds 2.7.2 (Light theme)
//  - cinny/src/colors.css.ts (Silver, Dark, Butter)
package top.s0cl.cinny.core.design

import androidx.compose.ui.graphics.Color

data class ContainerColors(
    val container: Color,
    val containerHover: Color,
    val containerActive: Color,
    val containerLine: Color,
    val onContainer: Color,
)

data class AccentColors(
    val main: Color,
    val mainHover: Color,
    val mainActive: Color,
    val mainLine: Color,
    val onMain: Color,
    val container: Color,
    val containerHover: Color,
    val containerActive: Color,
    val containerLine: Color,
    val onContainer: Color,
)

data class OtherColors(
    val focusRing: Color,
    val shadow: Color,
    val overlay: Color,
)

data class CinnyColors(
    val background: ContainerColors,
    val surface: ContainerColors,
    val surfaceVariant: ContainerColors,
    val primary: AccentColors,
    val secondary: AccentColors,
    val success: AccentColors,
    val warning: AccentColors,
    val critical: AccentColors,
    val other: OtherColors,
)

object CinnyThemes {
${themeBlocks}
}
`;
}

function generateScales() {
  const folds = JSON.parse(fs.readFileSync(foldsTokensPath, 'utf8'));
  const entries = (obj, fn) =>
    Object.entries(obj)
      .map(([key, value]) => `    val ${key} = ${fn(value)}`)
      .join('\n');
  return `// GENERATED FILE. Do not edit by hand.
// Run: node tools/design/generate-design-tokens.mjs
package top.s0cl.cinny.core.design

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object CinnyFontSize {
${entries(folds.fontSize, (v) => `${v}.sp`)}
}

object CinnyLineHeight {
${entries(folds.lineHeight, (v) => `${v}.sp`)}
}

object CinnyLetterSpacing {
${entries(folds.fontSize, () => '0.sp')}
}

object CinnyFontWeight {
${entries(folds.fontWeight, (v) => `FontWeight(${v})`)}
}

object CinnySpacing {
${entries(folds.space, (v) => `${v}.dp`)}
}

object CinnyRadii {
${entries(folds.radii, (v) => `${v}.dp`)}
}

object CinnyBorderWidth {
${entries(folds.borderWidth, (v) => `${v}.dp`)}
}

object CinnyOpacity {
${entries(folds.opacity, (v) => `${v}f`)}
}
`;
}

function writeOrCheck(file, content) {
  if (checkMode) {
    if (!fs.existsSync(file) || fs.readFileSync(file, 'utf8') !== content) {
      console.error(`OUT OF DATE: ${path.relative(root, file)}`);
      process.exitCode = 1;
    } else {
      console.log(`OK: ${path.relative(root, file)}`);
    }
    return;
  }
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content, 'utf8');
  console.log(`Wrote ${path.relative(root, file)}`);
}

writeOrCheck(path.join(outDir, 'CinnyColors.kt'), generateColors());
writeOrCheck(path.join(outDir, 'CinnyScales.kt'), generateScales());
