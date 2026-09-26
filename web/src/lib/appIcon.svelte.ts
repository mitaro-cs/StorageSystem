/** Значок сайта на выбор (lib/looks.ts ICONS): файлы — static/icons/v и manifest-*.webmanifest. */
export type AppIcon = 'light' | 'dark' | 'ocean' | 'forest' | 'sunset' | 'grape';

/** Картинка значка для вкладки и для логотипа внутри приложения. */
export function iconSrc(icon: AppIcon): string {
	return icon === 'light' ? '/favicon.svg?v=3' : `/icons/v/${icon}.svg?v=3`;
}

function stored(): AppIcon {
	try {
		const v = localStorage.getItem('gb-icon');
		return v === 'dark' || v === 'ocean' || v === 'forest' || v === 'sunset' || v === 'grape'
			? v
			: 'light';
	} catch {
		return 'light';
	}
}

/**
 * Выбранный значок (lib/theme.ts ICONS) — для логотипа внутри приложения: левая панель, «Все
 * группы», «Версия и обновления». Меняет setIcon().
 */
export const appIcon = $state<{ id: AppIcon }>({
	id: typeof localStorage === 'undefined' ? 'light' : stored()
});
