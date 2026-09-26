<script lang="ts">
	import { page } from '$app/state';
	import { bottomNav, isActive } from './nav';

	// Белый круг активного раздела не прыгает, а перетекает к нажатой иконке.
	const index = $derived(bottomNav.findIndex((i) => isActive(page.url.pathname, i.href)));
</script>

<!-- Плавающая матовая панель: иконки, активный раздел — белый круг. Подписи — для скринридеров. -->
<div class="fade" aria-hidden="true"></div>
<nav class="bottom-nav" aria-label="Основные разделы" style:--i={Math.max(index, 0)}>
	<span class="pill" class:hidden={index < 0} aria-hidden="true"></span>
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
	/* --vv-shift — поправка на ошибку iOS после клавиатуры, kb-open — пока печатают
	   (lib/shell/viewport.ts): клавиатура закрывает низ экрана, панель не нужна. */
	.fade,
	.bottom-nav {
		translate: 0 var(--vv-shift, 0px);
	}
	:global(:root.kb-open) .fade,
	:global(:root.kb-open) .bottom-nav {
		visibility: hidden;
	}
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
	.pill {
		position: absolute;
		top: 50%;
		left: calc(8px + (100% - 16px) / 5 * var(--i) + ((100% - 16px) / 5 - 48px) / 2);
		width: 48px;
		height: 48px;
		margin-top: -24px;
		border-radius: 50%;
		background: #fff;
		box-shadow: 0 4px 12px rgb(0 0 0 / 0.25);
		transition:
			left 380ms cubic-bezier(0.3, 1.35, 0.5, 1),
			opacity 200ms var(--ease);
	}
	.pill.hidden {
		opacity: 0;
	}
	a {
		position: relative;
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
		color: #0d0d0f;
		animation: nav-pop 460ms cubic-bezier(0.3, 1.7, 0.5, 1);
	}
	@keyframes nav-pop {
		30% {
			transform: scale(0.82);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.pill {
			transition: none;
		}
		a.on .icon {
			animation: none;
		}
	}
</style>
