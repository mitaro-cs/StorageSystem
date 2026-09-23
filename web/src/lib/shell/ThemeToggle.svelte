<script lang="ts">
	import { onMount } from 'svelte';
	import { Monitor, Moon, Sun } from '@lucide/svelte';
	import { currentTheme, setTheme, type Theme } from '$lib/theme';

	let theme = $state<Theme>('system');
	let changed = $state(false);
	onMount(() => (theme = currentTheme()));

	const next: Record<Theme, Theme> = { system: 'light', light: 'dark', dark: 'system' };
	const labels: Record<Theme, string> = {
		system: 'Тема: как в системе',
		light: 'Тема: светлая',
		dark: 'Тема: тёмная'
	};

	function toggle() {
		theme = next[theme];
		changed = true;
		setTheme(theme);
	}
</script>

<button class="theme" onclick={toggle} aria-label={labels[theme]} title={labels[theme]}>
	{#key theme}
		<span class="icon" class:spin={changed}>
			{#if theme === 'light'}<Sun size={18} />{:else if theme === 'dark'}<Moon
					size={18}
				/>{:else}<Monitor size={18} />{/if}
		</span>
	{/key}
</button>

<style>
	.theme {
		display: grid;
		place-items: center;
		width: 36px;
		height: 36px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: var(--text-2);
	}
	.theme:hover {
		background: var(--surface-2);
		color: var(--text);
	}
	.icon {
		display: grid;
	}
	.icon.spin {
		animation: spin-in 250ms var(--ease);
	}
	@keyframes spin-in {
		from {
			transform: rotate(-60deg) scale(0.7);
			opacity: 0;
		}
	}
</style>
