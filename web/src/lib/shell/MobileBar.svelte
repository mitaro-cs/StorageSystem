<script lang="ts">
	import { ChevronDown, Search } from '@lucide/svelte';
	import { t } from '$lib/i18n/ru';
	import { currentGroup, groups, isMulti, selectGroup, session } from '$lib/session.svelte';
	import ThemeToggle from './ThemeToggle.svelte';
	import Bell from './Bell.svelte';

	// Разделы на телефоне — в нижней панели (как в боковой панели компьютера), поиск — здесь,
	// участники и настройки — в профиле, поэтому отдельного меню нет.
	const title = $derived(
		currentGroup()?.name ??
			(isMulti() ? t.nav.allGroups : (session.me?.groups[0]?.name ?? 'groupbase'))
	);
</script>

<header class="bar">
	{#if isMulti()}
		<label class="title pick">
			<span class="sr-only">Группа</span>
			<select
				value={session.groupId === null ? '' : String(session.groupId)}
				onchange={(e) => selectGroup(e.currentTarget.value ? Number(e.currentTarget.value) : null)}
			>
				<option value="">{t.nav.allGroups}</option>
				{#each groups() as g (g.id)}<option value={String(g.id)}>{g.name}</option>{/each}
			</select>
			<span class="label">{title}</span>
			<ChevronDown size={18} />
		</label>
	{:else}
		<strong class="title">{title}</strong>
	{/if}
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
	/* Переключатель групп: настоящий select поверх подписи — системный выбор на телефоне */
	.pick {
		position: relative;
		display: flex;
		align-items: center;
		gap: 4px;
	}
	.pick .label {
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.pick :global(svg) {
		flex: none;
		color: var(--text-3);
	}
	.pick select {
		position: absolute;
		inset: 0;
		width: 100%;
		opacity: 0;
		font-size: 16px;
	}
</style>
