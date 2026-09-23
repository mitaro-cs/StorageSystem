<script lang="ts">
	import { fly } from '$lib/motion';

	let { children } = $props();
</script>

<main class="auth">
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
		background:
			radial-gradient(60rem 30rem at 50% -10%, var(--accent-soft), transparent 70%), var(--bg);
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
		width: min(420px, 100%);
		padding: var(--s6) var(--s5);
		box-shadow: var(--shadow-2);
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
