<script lang="ts">
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import { PanelLeftClose, PanelLeftOpen } from '@lucide/svelte';
	import { t } from '$lib/i18n/ru';
	import { currentGroup, isMulti, session } from '$lib/session.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import GroupSwitcher from './GroupSwitcher.svelte';
	import SubjectList from './SubjectList.svelte';
	import ThemeToggle from './ThemeToggle.svelte';
	import { isActive, mainNav } from './nav';

	let { collapsed = $bindable(false) }: { collapsed?: boolean } = $props();

	onMount(() => {
		try {
			collapsed = localStorage.getItem('gb-sidebar') === 'collapsed';
		} catch {
			/* по умолчанию развёрнута */
		}
	});

	function toggle() {
		collapsed = !collapsed;
		try {
			localStorage.setItem('gb-sidebar', collapsed ? 'collapsed' : 'open');
		} catch {
			/* не запоминаем */
		}
	}

	const group = $derived(currentGroup());
	const title = $derived(
		group?.name ??
			(isMulti()
				? session.me?.instance.name || t.nav.allGroups
				: (session.me?.groups[0]?.name ?? ''))
	);
</script>

<aside class="sidebar" class:collapsed aria-label="Навигация">
	<div class="top">
		{#if !collapsed}
			<GroupSwitcher />
			<div class="title">
				<strong>{title}</strong>
				{#if group?.university}<span class="faint small"
						>{group.university}{group.course ? `, ${group.course} курс` : ''}</span
					>{/if}
			</div>
		{/if}
	</div>

	<nav class="main">
		{#each mainNav as item (item.href)}
			{@const Icon = item.icon}
			<a
				href={item.href}
				class:active={isActive(page.url.pathname, item.href)}
				aria-current={isActive(page.url.pathname, item.href) ? 'page' : undefined}
				title={collapsed ? item.label : undefined}
			>
				<Icon size={19} strokeWidth={1.8} />
				{#if !collapsed}<span>{item.label}</span>{/if}
			</a>
		{/each}
	</nav>

	{#if !collapsed}
		<div class="section">
			<p class="eyebrow">{t.nav.subjects}</p>
			<SubjectList />
		</div>
	{/if}

	<div class="bottom">
		{#if session.me}
			<a class="me" href="/profile" title={session.me.user.displayName}>
				<Avatar
					id={session.me.user.id}
					name={session.me.user.displayName}
					avatar={session.me.user.avatar}
					size={30}
				/>
				{#if !collapsed}<span class="name">{session.me.user.displayName}</span>{/if}
			</a>
		{/if}
		{#if !collapsed}<ThemeToggle />{/if}
		<button
			class="collapse"
			onclick={toggle}
			aria-label={collapsed ? t.nav.expand : t.nav.collapse}
			title={collapsed ? t.nav.expand : t.nav.collapse}
		>
			{#if collapsed}<PanelLeftOpen size={18} />{:else}<PanelLeftClose size={18} />{/if}
		</button>
	</div>
</aside>

<style>
	.sidebar {
		position: sticky;
		top: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s4);
		width: var(--sidebar);
		height: 100dvh;
		padding: var(--s4) var(--s3);
		border-right: 1px solid var(--border);
		background: var(--bg);
		transition: width 220ms var(--ease);
		overflow: hidden;
	}
	.collapsed {
		width: var(--sidebar-collapsed);
		align-items: center;
	}
	.top {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		padding: 0 6px;
	}
	.title {
		display: flex;
		flex-direction: column;
		line-height: 1.3;
	}
	.title strong {
		font-size: 17px;
		letter-spacing: -0.02em;
	}
	.main {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.main a {
		display: flex;
		align-items: center;
		gap: 12px;
		height: 38px;
		padding: 0 10px;
		border-radius: 10px;
		color: var(--text-2);
		font-weight: 520;
		white-space: nowrap;
		transition:
			background-color var(--dur) var(--ease),
			color var(--dur) var(--ease);
	}
	.main a:hover {
		background: var(--surface-2);
		color: var(--text);
		text-decoration: none;
	}
	.main a.active {
		background: var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-1);
	}
	.main a.active :global(svg) {
		color: var(--accent);
	}
	.section {
		flex: 1;
		min-height: 0;
		overflow-y: auto;
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding-top: var(--s2);
		border-top: 1px solid var(--border);
	}
	.section .eyebrow {
		padding: 6px 10px 2px;
	}
	.collapsed .main {
		flex: 1;
	}
	.bottom {
		display: flex;
		align-items: center;
		gap: 4px;
		padding-top: var(--s2);
		border-top: 1px solid var(--border);
	}
	.collapsed .bottom {
		flex-direction: column;
		width: 100%;
	}
	.me {
		display: flex;
		align-items: center;
		gap: 10px;
		flex: 1;
		min-width: 0;
		padding: 4px 6px;
		border-radius: 10px;
		color: var(--text);
		font-weight: 550;
		font-size: 14px;
	}
	.me:hover {
		background: var(--surface-2);
		text-decoration: none;
	}
	.name {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.collapse {
		display: grid;
		place-items: center;
		width: 36px;
		height: 36px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: var(--text-3);
	}
	.collapse:hover {
		background: var(--surface-2);
		color: var(--text);
	}
</style>
