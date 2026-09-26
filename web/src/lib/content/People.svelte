<script lang="ts">
	import { Search, UserPlus } from '@lucide/svelte';
	import { absolute, canShare, copy, share } from '$lib/copy';
	import { plural } from '$lib/format';
	import { can, currentGroup, groups, loadMe, session } from '$lib/session.svelte';
	import type { Member } from '$lib/types';
	import MemberList from './MemberList.svelte';
	import { memberActions } from './memberActions';
	import Modal from '$lib/ui/Modal.svelte';
	import Button from '$lib/ui/Button.svelte';

	// Все люди группы (или всех групп) с ролями и управлением: раздел «Участники» и кабинет
	// старосты и администратора.
	let reload = $state(0);
	let query = $state('');
	let members = $state<Member[]>([]);
	let reset = $state<{ link: string; name: string } | null>(null);
	let adding = $state(false);

	const ids = $derived(session.groupId !== null ? [session.groupId] : groups().map((g) => g.id));
	const addGroup = $derived(
		[currentGroup(), ...groups()].find(
			(g) => g && (can('create_accounts', g.id) || can('create_invites', g.id))
		)
	);
	const counts = $derived({
		all: members.length,
		lead: members.filter((m) => m.role !== 'student').length,
		pending: members.filter((m) => m.status === 'pending').length
	});
</script>

<div class="people">
	<div class="tools">
		<label class="search">
			<Search size={17} />
			<input
				type="search"
				placeholder="Найти по имени или логину"
				bind:value={query}
				aria-label="Поиск людей"
			/>
		</label>
		{#if addGroup}
			<Button variant="primary" onclick={() => (adding = true)}
				><UserPlus size={17} /> Добавить людей</Button
			>
		{/if}
	</div>

	{#if counts.all}
		<p class="summary faint small num">
			{counts.all}
			{plural(counts.all, ['человек', 'человека', 'человек'])}{counts.lead
				? ` · староста и замы: ${counts.lead}`
				: ''}{counts.pending ? ` · ещё не вошли: ${counts.pending}` : ''}
		</p>
	{/if}

	<MemberList
		groupIds={ids}
		{reload}
		{query}
		onload={(m) => (members = m)}
		actions={(m, g) =>
			memberActions(m, g, {
				reload: () => reload++,
				resetLink: (path, who) => (reset = { link: absolute(path), name: who.displayName }),
				selfChanged: () => loadMe()
			})}
	/>
</div>

<!-- Добавление людей и QR-код — редкие: их код грузится при открытии. -->
{#if addGroup && adding}
	{#await import('./AddPeople.svelte') then m}
		<m.default bind:open={adding} groupId={addGroup.id} onadded={() => reload++} />
	{/await}
{/if}

<Modal open={reset !== null} title="Сброс пароля" onclose={() => (reset = null)}>
	<p class="muted">
		Покажите QR-код {reset?.name} — он отсканирует его камерой и задаст новый пароль. Или отправьте ссылку
		лично. Она одноразовая и действует 7 дней; старые сессии пользователя закроются.
	</p>
	{#if reset}<div class="qr">
			{#await import('$lib/ui/QrCode.svelte') then m}
				<m.default value={reset.link} label="QR-код для сброса пароля" />
			{/await}
		</div>{/if}
	<div class="linkbox"><code>{reset?.link}</code></div>
	{#snippet footer()}
		{#if canShare()}<Button onclick={() => reset && share(reset.link, 'Новый пароль для groupbase')}
				>Поделиться</Button
			>{/if}
		<Button variant="primary" onclick={() => reset && copy(reset.link, 'Ссылка скопирована')}
			>Скопировать</Button
		>
	{/snippet}
</Modal>

<style>
	.people {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.tools {
		display: flex;
		align-items: center;
		gap: var(--s2);
		flex-wrap: wrap;
	}
	.search {
		flex: 1 1 220px;
		display: flex;
		align-items: center;
		gap: 10px;
		height: 44px;
		padding: 0 14px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text-3);
	}
	.search:focus-within {
		border-color: var(--border-strong);
		box-shadow: 0 0 0 3px var(--accent-soft);
	}
	.search input {
		flex: 1;
		min-width: 0;
		height: 100%;
		border: 0;
		background: none;
		color: var(--text);
		font: inherit;
		outline: none;
	}
	.summary {
		margin: 0;
	}
	.qr {
		width: min(240px, 70%);
		margin: var(--s3) auto 0;
	}
	.linkbox {
		margin-top: var(--s3);
		padding: 12px;
		background: var(--surface-2);
		border-radius: var(--r-s);
		word-break: break-all;
		font-size: 13px;
	}
</style>
