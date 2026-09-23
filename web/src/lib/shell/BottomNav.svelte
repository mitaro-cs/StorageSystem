<script lang="ts">
	import { page } from '$app/state';
	import { bottomNav, isActive } from './nav';
</script>

<!-- Плавающая матовая панель: иконки, активный раздел — белый круг. Подписи — для скринридеров. -->
<div class="fade" aria-hidden="true"></div>
<nav class="bottom-nav" aria-label="Основные разделы">
	{#each bottomNav as item (item.href)}
		{@const Icon = item.icon}
		{@const on = isActive(page.url.pathname, item.href)}
		<a
			href={item.href}
			class:on
			aria-current={on ? 'page' : undefined}
			aria-label={item.label}
			title={item.label}
		>
			<span class="icon"><Icon size={22} strokeWidth={on ? 2 : 1.8} /></span>
		</a>
	{/each}
</nav>

<style>
	.fade {
		position: fixed;
		z-index: 39;
		left: 0;
		right: 0;
		bottom: 0;
		height: calc(var(--bottom-nav) + var(--bottom-gap) * 3 + env(safe-area-inset-bottom));
		background: linear-gradient(to bottom, transparent, var(--bg) 70%);
		pointer-events: none;
	}
	.bottom-nav {
		position: fixed;
		z-index: 40;
		left: var(--bottom-gap);
		right: var(--bottom-gap);
		bottom: calc(var(--bottom-gap) + env(safe-area-inset-bottom));
		max-width: 420px;
		margin: 0 auto;
		display: grid;
		grid-template-columns: repeat(5, 1fr);
		align-items: center;
		height: var(--bottom-nav);
		padding: 0 8px;
		border: 1px solid var(--glass-border);
		border-radius: var(--r-full);
		/* матовое стекло: сильное размытие и приглушённая прозрачность */
		background: var(--glass);
		backdrop-filter: blur(28px) saturate(1.5);
		-webkit-backdrop-filter: blur(28px) saturate(1.5);
		box-shadow:
			0 18px 40px -12px rgb(0 0 0 / 0.4),
			inset 0 1px 0 rgb(255 255 255 / 0.06);
	}
	a {
		display: grid;
		place-items: center;
		height: 100%;
		color: var(--glass-text);
		-webkit-tap-highlight-color: transparent;
	}
	a:hover {
		color: #fff;
		text-decoration: none;
	}
	.icon {
		display: grid;
		place-items: center;
		width: 48px;
		height: 48px;
		border-radius: 50%;
		transition:
			background-color 220ms var(--ease),
			color 220ms var(--ease),
			transform 150ms var(--ease);
	}
	a:active .icon {
		transform: scale(0.9);
	}
	a.on .icon {
		background: #fff;
		color: #0d0d0f;
		box-shadow: 0 4px 12px rgb(0 0 0 / 0.25);
	}
</style>
