/**
 * Mac или iPhone/iPad: там сочетания — с ⌘, у остальных (Windows, Linux, Android) — с Ctrl.
 * navigator.platform устарел, но есть везде; userAgentData — только в Chromium.
 */
export function isApple(): boolean {
	if (typeof navigator === 'undefined') return false;
	const ua = navigator as Navigator & { userAgentData?: { platform?: string } };
	const p = ua.userAgentData?.platform || navigator.platform || navigator.userAgent;
	return /mac|iphone|ipad|ipod/i.test(p);
}

/** Подпись сочетания: «⌘K» на Mac и «Ctrl K» на Windows. */
export function shortcut(key: string): string {
	return isApple() ? `⌘${key}` : `Ctrl ${key}`;
}

/**
 * Клавиша сочетания без оглядки на раскладку. При русской e.key — «л» вместо «k» и «.» вместо «/»,
 * и Ctrl K у людей не срабатывал. Латинская буква из e.key — как есть (у Dvorak и AZERTY свои места
 * букв), иначе — по физической клавише (e.code, названия по QWERTY).
 */
export function hotkey(e: Pick<KeyboardEvent, 'key' | 'code' | 'shiftKey'>): string {
	const key = e.key.length === 1 ? e.key.toLowerCase() : e.key;
	if (/^[a-z]$/.test(key)) return key;
	if (/^Key[A-Z]$/.test(e.code)) return e.code.slice(3).toLowerCase();
	if (e.code === 'Slash') return e.shiftKey ? '?' : '/';
	return key;
}
