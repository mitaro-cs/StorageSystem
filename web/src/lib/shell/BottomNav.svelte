<script lang="ts">
	import { page } from '$app/state';
	import { bottomNav, isActive } from './nav';
</script>

<nav class="bottom-nav" aria-label="Основные разделы">
	{#each bottomNav as item (item.href)}
		{@const Icon = item.icon}
		{@const on = isActive(page.url.pathname, item.href)}
		<a href={item.href} class:on aria-current={on ? 'page' : undefined}>
			<span class="icon"><Icon size={22} strokeWidth={on ? 2.1 : 1.7} /></span>
			<span class="label">{item.label}</span>
		</a>
	{/each}
</nav>

<style>
	.bottom-nav {
		position: fixed;
		z-index: 40;
		left: 0;
		right: 0;
		bottom: 0;
		display: grid;
		grid-template-columns: repeat(5, 1fr);
		height: calc(var(--bottom-nav) + env(safe-area-inset-bottom));
		padding-bottom: env(safe-area-inset-bottom);
		background: color-mix(in srgb, var(--surface) 88%, transparent);
		backdrop-filter: saturate(1.4) blur(14px);
		-webkit-backdrop-filter: saturate(1.4) blur(14px);
		border-top: 1px solid var(--border);
	}
	a {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 2px;
		color: var(--text-3);
		font-size: 11px;
		font-weight: 550;
		-webkit-tap-highlight-color: transparent;
	}
	a:hover {
		text-decoration: none;
	}
	.icon {
		display: grid;
		place-items: center;
		width: 48px;
		height: 30px;
		border-radius: 999px;
		transition:
			background-color var(--dur) var(--ease),
			transform 150ms var(--ease);
	}
	a:active .icon {
		transform: scale(0.92);
	}
	a.on {
		color: var(--text);
	}
	a.on .icon {
		background: var(--accent-soft);
		color: var(--accent);
	}
</style>
