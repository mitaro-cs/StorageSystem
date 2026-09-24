<script lang="ts">
	import { onMount } from 'svelte';
	import { fly } from '$lib/motion';
	import { cachedBackground, loadBackground, parseBackground } from '$lib/appearance';

	let { children } = $props();

	// Фон выбирает администратор; последний известный показываем сразу, пока спрашиваем сервер.
	let bg = $state(parseBackground(cachedBackground()));
	onMount(() => {
		loadBackground()
			.then((v) => (bg = parseBackground(v)))
			.catch(() => {});
	});
	const dark = $derived(bg.preset === 'night' || !!bg.image);
</script>

<main
	class="auth {bg.preset ? `login-bg-${bg.preset}` : ''}"
	class:photo={!!bg.image}
	class:on-dark={dark}
	style:--photo={bg.image ? `url(${bg.image})` : null}
>
	<div class="brand" aria-hidden="true">
		<svg viewBox="0 0 32 32"
			><rect width="32" height="32" rx="9" class="tile" /><path
				d="M9 11.5 16 8l7 3.5-7 3.5-7-3.5Z M9 16l7 3.5 7-3.5 M9 20.5 16 24l7-3.5"
				class="mark"
			/></svg
		>
		<span>groupbase</span>
	</div>
	<div class="card panel" in:fly={{ y: 12 }}>
		{@render children()}
	</div>
	<p class="foot faint small">Данные группы хранятся только на её сервере</p>
</main>

<style>
	.auth {
		min-height: 100dvh;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: var(--s5);
		padding: var(--s6) var(--s4);
		/* Сам фон — глобальные классы .login-bg-* (app.css): здесь его не задаём, иначе перебьём. */
	}
	.photo {
		background:
			linear-gradient(rgb(8 10 16 / 0.28), rgb(8 10 16 / 0.42)),
			var(--photo) center / cover no-repeat,
			var(--bg);
	}
	.on-dark .brand,
	.on-dark .foot {
		color: #fff;
		text-shadow: 0 1px 12px rgb(0 0 0 / 0.35);
	}
	.brand {
		display: flex;
		align-items: center;
		gap: 10px;
		font-weight: 650;
		font-size: 18px;
		letter-spacing: -0.02em;
	}
	.brand svg {
		width: 32px;
		height: 32px;
	}
	.tile {
		fill: var(--accent);
	}
	.mark {
		fill: none;
		stroke: var(--accent-text);
		stroke-width: 2;
		stroke-linecap: round;
		stroke-linejoin: round;
	}
	.panel {
		width: min(440px, 100%);
		padding: var(--s6) var(--s5);
		box-shadow: var(--shadow-3);
		background: color-mix(in srgb, var(--surface) 94%, transparent);
		backdrop-filter: blur(14px);
		-webkit-backdrop-filter: blur(14px);
	}
	.panel :global(h1) {
		font-size: 22px;
		margin-bottom: 6px;
	}
	.panel :global(form) {
		display: flex;
		flex-direction: column;
		gap: var(--s4);
		margin-top: var(--s5);
	}
	.foot {
		text-align: center;
	}
</style>
