<script lang="ts">
	import { Search } from '@lucide/svelte';
	import { t } from '$lib/i18n/ru';
	import { currentGroup, session } from '$lib/session.svelte';
	import ThemeToggle from './ThemeToggle.svelte';
	import Bell from './Bell.svelte';

	// Разделы на телефоне – в нижней панели (как в боковой панели компьютера), поиск – здесь,
	// участники и настройки – в профиле, поэтому отдельного меню нет.
	// Сайт одной группы (0.9.7): в заголовке – её название.
	const title = $derived(currentGroup()?.name ?? session.me?.groups[0]?.name ?? 'Campus');
</script>

<header class="bar">
	<strong class="title">{title}</strong>
	<a class="circle" href="/search" aria-label={t.nav.search} title={t.nav.search}
		><Search size={19} /></a
	>
	<Bell />
	<ThemeToggle />
</header>

<style>
	.bar {
		position: sticky;
		top: 0;
		z-index: 30;
		display: flex;
		align-items: center;
		gap: var(--s3);
		height: calc(64px + env(safe-area-inset-top));
		padding: env(safe-area-inset-top) var(--s4) 0;
		background: color-mix(in srgb, var(--bg) 80%, transparent);
		backdrop-filter: blur(24px) saturate(1.5);
		-webkit-backdrop-filter: blur(24px) saturate(1.5);
	}
	.title {
		flex: 1;
		min-width: 0;
		font-size: 21px;
		font-weight: 700;
		letter-spacing: -0.02em;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
</style>
