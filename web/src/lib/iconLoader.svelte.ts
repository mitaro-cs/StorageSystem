import type { Component } from 'svelte';

type Icon = Component<{ size?: number | string; strokeWidth?: number | string }>;

/**
 * Картинки иконок предметов подгружаются отдельным куском после первой отрисовки: страница
 * открывается быстрее, а подложки нужного цвета стоят на месте иконок, пока они не пришли.
 */
export const icons = $state<{ map: Record<string, Icon> | null }>({ map: null });

let loading: Promise<void> | null = null;

export function loadIcons(): Promise<void> {
	loading ??= import('./subjectIconSet').then((m) => {
		icons.map = m.ICONS;
	});
	return loading;
}
