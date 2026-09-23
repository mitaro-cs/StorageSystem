<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { Pin, PinOff, Users } from '@lucide/svelte';
	import { tick } from 'svelte';
	import { get, post, put } from '$lib/api';
	import { loadSubjects, sortedSubjects } from '$lib/data.svelte';
	import { plural } from '$lib/format';
	import { session } from '$lib/session.svelte';
	import { track } from '$lib/recent';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { Subject } from '$lib/types';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';
	import BackBar from '$lib/ui/BackBar.svelte';
	import SubjectArt from '$lib/ui/SubjectArt.svelte';
	import Modal from '$lib/ui/Modal.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import NewsFeed from '$lib/content/NewsFeed.svelte';
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
			track({ type: 'subject', id: subject.id, title: subject.name, color: subject.color });
		} catch {
			missing = true;
		}
	}

	$effect(() => {
		void id;
		load();
	});

	// Полоса миниатюр предметов: текущий крупнее и прокручен в видимую область.
	let strip: HTMLElement | undefined = $state();
	const others = $derived(sortedSubjects(session.groupId));
	$effect(() => {
		void id;
		void others.length;
		tick().then(() =>
			strip
				?.querySelector('[aria-current="page"]')
				?.scrollIntoView({ inline: 'center', block: 'nearest' })
		);
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
	<BackBar href="/subjects" label="Предметы">
		<button
			class="circle"
			onclick={togglePin}
			aria-label={subject.pinned ? 'Открепить' : 'Закрепить в боковой панели'}
			title={subject.pinned ? 'Открепить' : 'Закрепить'}
		>
			{#if subject.pinned}<PinOff size={18} />{:else}<Pin size={18} />{/if}
		</button>
		<Menu items={actions} />
	</BackBar>

	{#if others.length > 1}
		<nav class="strip" aria-label="Другие предметы" bind:this={strip}>
			{#each others as o (o.id)}
				<a
					href="/subjects/{o.id}"
					class="thumb"
					class:on={o.id === subject.id}
					aria-current={o.id === subject.id ? 'page' : undefined}
					title={o.name}
					aria-label={o.name}
					data-sveltekit-replacestate
				>
					<SubjectArt id={o.id} name={o.name} color={o.color} avatar={o.avatar} class="fill" />
				</a>
			{/each}
		</nav>
	{/if}

	<header class="hero">
		<span class="cover">
			<SubjectArt
				id={subject.id}
				name={subject.name}
				color={subject.color}
				avatar={subject.avatar}
				class="fill"
			/>
		</span>
		<div class="hero-info">
			<h1>{subject.name}</h1>
			<span class="sub">{subject.teacher || 'Преподаватель не указан'}</span>
			<span class="facts">
				<span
					><Users size={15} />
					<span class="num">{subject.groups.length}</span>
					{plural(subject.groups.length, ['группа', 'группы', 'групп'])}</span
				>
				{#if subject.pinned}<span><Pin size={15} /> закреплён</span>{/if}
			</span>
		</div>
	</header>

	<dl class="kv info">
		<div>
			<dt>Преподаватель</dt>
			<dd>{subject.teacher || '—'}</dd>
		</div>
		<div>
			<dt>{subject.groups.length > 1 ? 'Общий для групп' : 'Группа'}</dt>
			<dd>{subject.groups.map((g) => g.name).join(', ')}</dd>
		</div>
		<div>
			<dt>Статус</dt>
			<dd>{subject.archived ? 'В архиве' : 'Идёт'}</dd>
		</div>
	</dl>

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

	<!-- Вкладки подгружаются при открытии: код ДЗ, материалов и участников не нужен для ленты. -->
	{#if tab === 'homework'}
		{#await import('$lib/content/HomeworkBoard.svelte')}<Skeleton lines={4} />{:then m}<m.default
				subjectId={subject.id}
				title={false}
			/>{/await}
	{:else if tab === 'materials'}
		{#await import('$lib/content/MaterialBrowser.svelte')}<Skeleton lines={4} />{:then m}<m.default
				subjectId={subject.id}
				subjectName={subject.name}
			/>{/await}
	{:else if tab === 'members'}
		{#await import('$lib/content/MemberList.svelte')}<Skeleton lines={4} />{:then m}<m.default
				groupIds={subject.groups.map((g) => g.id)}
			/>{/await}
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
	.strip {
		display: flex;
		align-items: center;
		gap: 10px;
		margin: 0 calc(-1 * var(--s4)) var(--s4);
		padding: 6px var(--s4);
		overflow-x: auto;
		scrollbar-width: none;
	}
	.strip::-webkit-scrollbar {
		display: none;
	}
	.thumb {
		position: relative;
		flex: none;
		width: 60px;
		height: 72px;
		border-radius: 16px;
		opacity: 0.75;
		transition:
			width 220ms var(--ease),
			height 220ms var(--ease),
			opacity var(--dur) var(--ease);
	}
	.thumb:hover {
		opacity: 1;
	}
	.thumb.on {
		width: 84px;
		height: 96px;
		opacity: 1;
		box-shadow:
			0 0 0 3px var(--bg),
			0 0 0 5px var(--text);
		border-radius: 18px;
	}
	.thumb :global(.fill),
	.cover :global(.fill) {
		position: absolute;
		inset: 0;
		border-radius: inherit;
	}
	.hero {
		display: grid;
		grid-template-columns: minmax(120px, 40%) 1fr;
		gap: var(--s4);
		padding: 12px;
		margin-bottom: var(--s2);
		border-radius: var(--r-xl);
		background: var(--inverse);
		color: var(--inverse-text);
		box-shadow: var(--shadow-2);
	}
	.cover {
		position: relative;
		min-height: 150px;
		border-radius: 18px;
	}
	.hero-info {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
		padding: 6px 4px 2px 0;
	}
	.hero h1 {
		font-size: clamp(21px, 4.6vw, 28px);
		overflow-wrap: anywhere;
	}
	.sub {
		color: var(--inverse-muted);
	}
	.facts {
		display: inline-flex;
		flex-wrap: wrap;
		align-self: flex-start;
		align-items: center;
		margin-top: auto;
		padding: 8px 4px;
		border-radius: 14px;
		background: var(--inverse-2);
		color: var(--inverse-muted);
		font-size: 13.5px;
	}
	.facts > span {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 0 10px;
	}
	.facts > span + span {
		border-left: 1px solid color-mix(in srgb, var(--inverse-muted) 40%, transparent);
	}
	.info {
		margin-bottom: var(--s4);
	}
	.subtabs {
		display: flex;
		gap: 8px;
		margin: 0 calc(-1 * var(--s4)) var(--s4);
		padding: 2px var(--s4);
		overflow-x: auto;
		scrollbar-width: none;
	}
	.subtabs::-webkit-scrollbar {
		display: none;
	}
	.subtabs a {
		flex: none;
		height: 40px;
		display: grid;
		place-items: center;
		padding: 0 18px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text-2);
		font-weight: 550;
		font-size: 15px;
		transition:
			background-color var(--dur) var(--ease),
			color var(--dur) var(--ease);
	}
	.subtabs a:hover {
		text-decoration: none;
		color: var(--text);
	}
	.subtabs a.active {
		background: var(--accent);
		border-color: var(--accent);
		color: var(--accent-text);
	}
	@media (min-width: 900px) {
		.strip,
		.subtabs {
			margin-left: 0;
			margin-right: 0;
			padding-left: 2px;
			padding-right: 2px;
		}
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
	/* Узко — пункты переносятся: разделитель-черта тогда только мешает */
	@media (max-width: 480px) {
		.facts > span + span {
			border-left: 0;
		}
	}
</style>
