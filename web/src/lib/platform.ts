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
