<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { Pin, PinOff, Users } from '@lucide/svelte';
	import { get, post, put } from '$lib/api';
	import { loadSubjects } from '$lib/data.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { Subject } from '$lib/types';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';
	import Modal from '$lib/ui/Modal.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import NewsFeed from '$lib/content/NewsFeed.svelte';
	import HomeworkBoard from '$lib/content/HomeworkBoard.svelte';
	import MemberList from '$lib/content/MemberList.svelte';
	import MaterialBrowser from '$lib/content/MaterialBrowser.svelte';
	import SubjectEditor from '$lib/content/SubjectEditor.svelte';

	let subject = $state<Subject | null>(null);
	let missing = $state(false);
	let editor = $state(false);
	let share = $state(false);
	let directory = $state<{ id: number; name: string; university: string }[]>([]);
	let shareTo = $state<number | null>(null);

	const id = $derived(Number(page.params.id));
	const tab = $derived(page.url.searchParams.get('tab') ?? 'feed');
	const tabs = $derived([
		{ label: 'Лента', href: `/subjects/${id}` },
		{ label: 'ДЗ', href: `/subjects/${id}?tab=homework` },
		{ label: 'Материалы', href: `/subjects/${id}?tab=materials` },
		{ label: 'Участники', href: `/subjects/${id}?tab=members` }
	]);

	async function load() {
		try {
			subject = await get<Subject>(`/api/subjects/${id}`);
			missing = false;
		} catch {
			missing = true;
		}
	}

	$effect(() => {
		void id;
		load();
	});

	async function togglePin() {
		if (!subject) return;
		await put(`/api/subjects/${subject.id}/pinned`, { value: !subject.pinned });
		subject.pinned = !subject.pinned;
		loadSubjects();
	}

	async function openShare() {
		directory = await get('/api/groups/directory');
		shareTo = null;
		share = true;
	}

	async function doShare() {
		if (!subject || shareTo === null) return;
		try {
			const r = await post<{ status: string }>(`/api/subjects/${subject.id}/links`, {
				groupId: shareTo
			});
			toast(
				r.status === 'linked' ? 'Предмет стал общим' : 'Запрос отправлен старосте группы',
				'ok'
			);
			share = false;
			load();
			loadSubjects();
		} catch (e) {
			toastError(e);
		}
	}

	const actions = $derived.by((): MenuItem[] => {
		if (!subject) return [];
		const s = subject;
		const out: MenuItem[] = [];
		if (s.can.edit) out.push({ label: 'Изменить', onclick: () => (editor = true) });
		if (s.can.share) out.push({ label: 'Сделать общим с группой…', onclick: openShare });
		if (s.can.edit)
			out.push({
				label: s.archived ? 'Вернуть из архива' : 'В архив',
				onclick: async () => {
					try {
						await put(`/api/subjects/${s.id}/archived`, { value: !s.archived });
						await loadSubjects();
						if (!s.archived) goto('/subjects');
						else load();
					} catch (e) {
						toastError(e);
					}
				}
			});
		return out;
	});

	// Активную вкладку-ссылку определяем по параметру, а не только по пути.
	const activeTabs = $derived(
		tabs
			.map((t) => ({ ...t, href: t.href }))
			.map((t, i) => ({
				...t,
				active: ['feed', 'homework', 'materials', 'members'][i] === tab
			}))
	);
</script>

<svelte:head><title>{subject?.name ?? 'Предмет'} · groupbase</title></svelte:head>

{#if missing}
	<div class="card">
		<Empty title="Предмет не найден" text="Его нет или он не связан с вашей группой." />
	</div>
{:else if !subject}
	<Skeleton lines={4} />
{:else}
	<header class="head" style:--c={subject.color}>
		<nav class="crumbs small" aria-label="Путь">
			<a href="/subjects">Предметы</a><span aria-hidden="true">/</span><span aria-current="page"
				>{subject.name}</span
			>
		</nav>
		<div class="title-row">
			<span class="swatch" aria-hidden="true"></span>
			<h1>{subject.name}</h1>
			<button
				class="pin"
				onclick={togglePin}
				aria-label={subject.pinned ? 'Открепить' : 'Закрепить в боковой панели'}
				title={subject.pinned ? 'Открепить' : 'Закрепить'}
			>
				{#if subject.pinned}<PinOff size={18} />{:else}<Pin size={18} />{/if}
			</button>
			<Menu items={actions} />
		</div>
		<p class="muted">
			{subject.teacher}
			{#if subject.groups.length > 1}
				<span class="chip accent"
					><Users size={12} /> {subject.groups.map((g) => g.name).join(', ')}</span
				>
			{/if}
			{#if subject.archived}<span class="chip">в архиве</span>{/if}
		</p>
	</header>

	<nav class="subtabs" aria-label="Разделы предмета">
		{#each activeTabs as t (t.label)}
			<a
				href={t.href}
				class:active={t.active}
				aria-current={t.active ? 'page' : undefined}
				data-sveltekit-noscroll
				data-sveltekit-replacestate>{t.label}</a
			>
		{/each}
	</nav>

	{#if tab === 'homework'}
		<HomeworkBoard subjectId={subject.id} title={false} />
	{:else if tab === 'materials'}
		<MaterialBrowser subjectId={subject.id} subjectName={subject.name} />
	{:else if tab === 'members'}
		<MemberList groupIds={subject.groups.map((g) => g.id)} />
	{:else}
		<NewsFeed subjectId={subject.id} />
	{/if}

	<SubjectEditor
		bind:open={editor}
		edit={subject}
		onsaved={(s) => ((subject = s), loadSubjects())}
	/>
	<Modal bind:open={share} title="Общий предмет">
		<p class="muted">
			Предмет и его материалы увидит выбранная группа. Если вы не староста этой группы, её староста
			получит запрос.
		</p>
		<div class="options">
			{#each directory.filter((g) => !subject?.groups.some((x) => x.id === g.id)) as g (g.id)}
				<label class="check opt"
					><input type="radio" bind:group={shareTo} value={g.id} />
					<span>{g.name} <span class="faint small">{g.university}</span></span></label
				>
			{:else}
				<p class="faint">Других групп в инстансе нет</p>
			{/each}
		</div>
		{#snippet footer()}
			<Button onclick={() => (share = false)}>Отмена</Button>
			<Button variant="primary" disabled={shareTo === null} onclick={doShare}>Связать</Button>
		{/snippet}
	</Modal>
{/if}

<style>
	.head {
		margin-bottom: var(--s4);
	}
	.crumbs {
		display: flex;
		gap: 6px;
		color: var(--text-3);
		margin-bottom: var(--s2);
	}
	.crumbs a {
		color: var(--text-2);
	}
	.title-row {
		display: flex;
		align-items: center;
		gap: 12px;
	}
	.title-row h1 {
		flex: 1;
		min-width: 0;
	}
	.swatch {
		width: 14px;
		height: 32px;
		border-radius: 5px;
		background: var(--c);
		flex: none;
	}
	.pin {
		display: grid;
		place-items: center;
		width: 36px;
		height: 36px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: var(--text-3);
	}
	.pin:hover {
		background: var(--surface-2);
		color: var(--text);
	}
	.head p {
		display: flex;
		align-items: center;
		gap: 8px;
		margin-top: 6px;
		padding-left: 26px;
		flex-wrap: wrap;
	}
	.subtabs {
		display: flex;
		gap: 4px;
		padding: 4px;
		margin-bottom: var(--s4);
		background: var(--surface-2);
		border-radius: var(--r);
		overflow-x: auto;
	}
	.subtabs a {
		flex: none;
		height: 32px;
		display: grid;
		place-items: center;
		padding: 0 14px;
		border-radius: 9px;
		color: var(--text-2);
		font-weight: 550;
		font-size: 14px;
		transition: all var(--dur) var(--ease);
	}
	.subtabs a:hover {
		text-decoration: none;
		color: var(--text);
	}
	.subtabs a.active {
		background: var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-1);
	}
	.options {
		display: flex;
		flex-direction: column;
		gap: 4px;
		margin-top: var(--s3);
	}
	.opt {
		padding: 10px 12px;
		border-radius: var(--r-s);
	}
	.opt:hover {
		background: var(--surface-2);
	}
</style>
