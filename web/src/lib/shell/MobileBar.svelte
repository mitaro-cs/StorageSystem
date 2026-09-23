<script lang="ts">
	import { Menu as MenuIcon } from '@lucide/svelte';
	import { page } from '$app/state';
	import { t } from '$lib/i18n/ru';
	import { currentGroup, isMulti, session } from '$lib/session.svelte';
	import GroupSwitcher from './GroupSwitcher.svelte';
	import SubjectList from './SubjectList.svelte';
	import ThemeToggle from './ThemeToggle.svelte';
	import { isActive, mainNav } from './nav';

	let open = $state(false);
	let dialog: HTMLDialogElement | undefined = $state();

	$effect(() => {
		if (!dialog) return;
		if (open && !dialog.open) dialog.showModal();
		if (!open && dialog.open) dialog.close();
	});

	const title = $derived(
		currentGroup()?.name ??
			(isMulti() ? t.nav.allGroups : (session.me?.groups[0]?.name ?? 'groupbase'))
	);
</script>

<header class="bar">
	<button
		class="menu"
		onclick={() => (open = true)}
		aria-label="Меню и предметы"
		aria-haspopup="dialog"
	>
		<MenuIcon size={22} />
	</button>
	<strong class="title">{title}</strong>
	<ThemeToggle />
</header>

<dialog
	bind:this={dialog}
	class="sheet"
	onclose={() => (open = false)}
	onclick={(e) => e.target === dialog && (open = false)}
	aria-label="Меню"
>
	{#if open}
		<div class="inner">
			<div class="grab" aria-hidden="true"></div>
			<GroupSwitcher compact />
			<nav class="links">
				{#each mainNav as item (item.href)}
					{@const Icon = item.icon}
					<a
						href={item.href}
						class:active={isActive(page.url.pathname, item.href)}
						onclick={() => (open = false)}
					>
						<Icon size={20} strokeWidth={1.8} /><span>{item.label}</span>
					</a>
				{/each}
			</nav>
			<p class="eyebrow">{t.nav.subjects}</p>
			<SubjectList onnavigate={() => (open = false)} />
		</div>
	{/if}
</dialog>

<style>
	.bar {
		position: sticky;
		top: 0;
		z-index: 30;
		display: flex;
		align-items: center;
		gap: var(--s2);
		height: 56px;
		padding: 0 var(--s2);
		padding-top: env(safe-area-inset-top);
		background: color-mix(in srgb, var(--bg) 88%, transparent);
		backdrop-filter: saturate(1.4) blur(14px);
		-webkit-backdrop-filter: saturate(1.4) blur(14px);
	}
	.menu {
		display: grid;
		place-items: center;
		width: 44px;
		height: 44px;
		border: 0;
		border-radius: 12px;
		background: transparent;
	}
	.title {
		flex: 1;
		font-size: 17px;
		letter-spacing: -0.02em;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.sheet {
		width: 100vw;
		max-width: 100vw;
		max-height: 85dvh;
		margin: auto 0 0;
		padding: 0;
		border: 0;
		border-radius: 20px 20px 0 0;
		background: var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-3);
	}
	.sheet[open] {
		animation: up 260ms var(--ease);
	}
	.sheet::backdrop {
		background: var(--overlay);
	}
	.inner {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		padding: 8px var(--s4) calc(var(--s5) + env(safe-area-inset-bottom));
	}
	.grab {
		width: 40px;
		height: 4px;
		margin: 0 auto 4px;
		border-radius: 2px;
		background: var(--border-strong);
	}
	.links {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 4px;
	}
	.links a {
		display: flex;
		align-items: center;
		gap: 10px;
		height: 44px;
		padding: 0 12px;
		border-radius: 12px;
		background: var(--surface-2);
		color: var(--text);
		font-weight: 520;
	}
	.links a.active {
		background: var(--accent-soft);
		color: var(--accent);
	}
	.links a:hover {
		text-decoration: none;
	}
	@keyframes up {
		from {
			transform: translateY(30%);
			opacity: 0.5;
		}
	}
</style>
