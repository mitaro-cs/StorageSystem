<script lang="ts">
	import { subjectById } from '$lib/data.svelte';
	import { subjectIcon } from '$lib/subjectIcons';

	// Значок предмета: иконка цвета предмета на лёгкой подложке того же цвета.
	interface Props {
		id?: number;
		name: string;
		color: string;
		/** Ключ иконки; не передан — берётся из загруженного списка предметов. */
		icon?: string | null;
		size?: number;
		/** Без подложки — только иконка (для строк текста). */
		bare?: boolean;
	}

	let { id, name, color, icon = undefined, size = 28, bare = false }: Props = $props();

	const key = $derived(icon === undefined && id !== undefined ? subjectById(id)?.icon : icon);
	const glyph = $derived(subjectIcon(key, name));
</script>

<span
	class="glyph"
	class:bare
	style:--c={color}
	style:background={bare ? 'none' : null}
	style:width="{size}px"
	style:height="{size}px"
	title={glyph.label}
	aria-hidden="true"
>
	<glyph.icon size={bare ? size : Math.round(size * 0.58)} strokeWidth={2.1} />
</span>

<style>
	.glyph {
		flex: none;
		display: inline-grid;
		place-items: center;
		border-radius: 30%;
		color: var(--c);
		background: color-mix(in srgb, var(--c) 14%, transparent);
	}
	.bare {
		border-radius: 0;
	}
	:global([data-theme='dark']) .glyph {
		color: color-mix(in srgb, var(--c) 70%, white);
		background: color-mix(in srgb, var(--c) 24%, transparent);
	}
	@media (prefers-color-scheme: dark) {
		:global(:root:not([data-theme='light'])) .glyph {
			color: color-mix(in srgb, var(--c) 70%, white);
			background: color-mix(in srgb, var(--c) 24%, transparent);
		}
	}
</style>
