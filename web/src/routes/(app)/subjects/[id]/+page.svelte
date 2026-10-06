<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { Archive, EyeOff, Pencil, Pin, PinOff, Plus, Send, Upload, Users } from '@lucide/svelte';
	import { get, put } from '$lib/api';
	import { loadSubjects, sortedSubjects } from '$lib/data.svelte';
	import { can, session } from '$lib/session.svelte';
	import { track } from '$lib/recent';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { Subject } from '$lib/types';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';
	import BackBar from '$lib/ui/BackBar.svelte';
	import SubjectArt from '$lib/ui/SubjectArt.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';

	let subject = $state<Subject | null>(null);
	let missing = $state(false);
	let editor = $state(false);
	let share = $state(false);
	// Добавить прямо отсюда: задание, новость, файл — без поиска кнопки во вкладках.
	let hwOpen = $state(false);
	let newsOpen = $state(false);
	let fileOpen = $state(false);
	// После добавления вкладка перезагружается (номер меняется — {#key} пересоздаёт её).
	let refresh = $state(0);

	const id = $derived(Number(page.params.id));
	// Первая вкладка — задания, новости предмета — сразу за ними (0.6, просьба владельца).
	const tab = $derived(page.url.searchParams.get('tab') ?? 'homework');
	// «Пары» — если у предмета есть расписание.
	const tabs = $derived(
		[
			{ value: 'homework', label: 'ДЗ' },
			{ value: 'feed', label: 'Новости' },
			...(subject?.lessons ? [{ value: 'lessons', label: 'Пары' }] : []),
			{ value: 'materials', label: 'Материалы' },
			{ value: 'members', label: 'Участники' }
		].map((t) => ({
			...t,
			href: t.value === 'homework' ? `/subjects/${id}` : `/subjects/${id}?tab=${t.value}`
		}))
	);

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

	// Полоса миниатюр предметов — отдельным кусочком (SubjectStrip): место под неё занято сразу.
	const others = $derived(sortedSubjects(session.groupId));

	async function togglePin() {
		if (!subject) return;
		await put(`/api/subjects/${subject.id}/pinned`, { value: !subject.pinned });
		subject.pinned = !subject.pinned;
		loadSubjects();
	}

	/** «Не мой предмет»: другая подгруппа — убрать из общих списков и уведомлений, или вернуть. */
	async function setMine(mine: boolean) {
		if (!subject) return;
		try {
			await put(`/api/subjects/${subject.id}/mine`, { value: mine });
			subject.mine = mine;
			await loadSubjects();
			toast(
				mine
					? 'Предмет снова ваш: его задания и новости — в общих списках'
					: 'Скрыто: задания, новости и уведомления этого предмета больше не придут',
				'ok'
			);
		} catch (e) {
			toastError(e);
		}
	}

	// Что можно в этом предмете: права — в любой из его групп.
	const inGroups = (perm: Parameters<typeof can>[0]) =>
		!!subject?.groups.some((g) => can(perm, g.id));
	const canHomework = $derived(inGroups('publish_homework'));
	const canNews = $derived(inGroups('publish_news'));
	const canUpload = $derived(inGroups('upload_materials'));
	const canFiles = $derived(canUpload || inGroups('suggest_materials'));

	/** Добавили — открываем вкладку, где это видно, и обновляем её. */
	function added(tab: 'feed' | 'homework' | 'materials') {
		refresh++;
		goto(`/subjects/${id}?tab=${tab}`, {
			replaceState: true,
			noScroll: true,
			keepFocus: true
		});
	}

	// Редкое — в меню «…»: общий предмет, архив, «не мой предмет», подгруппы, удалить.
	const actions = $derived.by((): MenuItem[] => {
		if (!subject) return [];
		const s = subject;
		const out: MenuItem[] = [];
		out.push(
			s.mine === false
				? { label: 'Мой предмет — вернуть в списки', onclick: () => setMine(true) }
				: { label: 'Не мой предмет (другая подгруппа)', onclick: () => setMine(false) }
		);
		if (s.can.share) out.push({ label: 'Сделать общим с группой…', onclick: () => (share = true) });
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
		if (s.can.edit) {
			out.push({
				label: 'Разделить на подгруппы…',
				onclick: () => import('$lib/content/subjectAdmin').then((m) => m.splitSubject(s))
			});
			out.push({
				label: 'Удалить предмет…',
				danger: true,
				onclick: () => import('$lib/content/subjectAdmin').then((m) => m.deleteSubject(s))
			});
		}
		return out;
	});

	// Активную вкладку-ссылку определяем по параметру, а не только по пути.
	const activeTabs = $derived(tabs.map((t) => ({ ...t, active: t.value === tab })));
</script>

<svelte:head><title>{subject?.name ?? 'Предмет'} · campus</title></svelte:head>

{#if missing}
	<div class="card">
		<Empty title="Предмет не найден" text="Его нет или он не связан с вашей группой." />
	</div>
{:else if !subject}
	<Skeleton lines={4} />
{:else}
	<BackBar href="/subjects" label="Предметы" />

	{#if others.length > 1}
		<div class="strip-slot">
			{#await import('$lib/content/SubjectStrip.svelte') then m}<m.default
					subjects={others}
					current={subject.id}
				/>{/await}
		</div>
	{/if}

	<!-- Подгруппы «№1», «№2»: выбор своей (код — только у таких предметов). -->
	{#if /№\s*\d|\(\s*\d+\s*\)|\d\s*(под)?гр/i.test(subject.name)}
		{#await import('$lib/content/SubgroupSwitch.svelte') then m}<m.default {subject} />{/await}
	{/if}

	{#if subject.mine === false}
		<div class="not-mine card" role="status">
			<EyeOff size={18} />
			<p>
				<strong>Не ваш предмет.</strong>
				<span class="muted"
					>Его задания и новости не показываются в общих списках и не приходят уведомлениями.</span
				>
			</p>
			<Button size="s" onclick={() => setMine(true)}>Это мой предмет</Button>
		</div>
	{/if}

	<header class="hero">
		<span class="cover">
			<SubjectArt
				id={subject.id}
				name={subject.name}
				color={subject.color}
				avatar={subject.avatar}
				icon={subject.icon}
				class="fill"
			/>
		</span>
		<div class="hero-info">
			<h1>{subject.name}</h1>
			<span class="sub">{subject.teacher || 'Преподаватель не указан'}</span>
			<span class="facts">
				<span><Users size={15} /> {subject.groups.map((g) => g.name).join(', ')}</span>
				{#if subject.archived}<span><Archive size={15} /> в архиве</span>{/if}
				{#if subject.pinned}<span><Pin size={15} /> закреплён</span>{/if}
			</span>
			{#if subject.chatUrl}
				<a class="chat" href={subject.chatUrl} target="_blank" rel="noreferrer"
					><Send size={16} /> Чат предмета</a
				>
			{/if}
		</div>
	</header>

	<!-- Управление предметом — одной строкой: добавить, закрепить, изменить, остальное — в «…». -->
	<div class="toolbar" role="toolbar" aria-label="Действия с предметом">
		{#if canHomework}
			<Button size="s" variant="primary" onclick={() => (hwOpen = true)}
				><Plus size={16} /> Задание</Button
			>
		{/if}
		{#if canNews}
			<Button size="s" onclick={() => (newsOpen = true)}><Plus size={16} /> Новость</Button>
		{/if}
		{#if canFiles}
			<Button size="s" onclick={() => (fileOpen = true)}
				><Upload size={16} /> {canUpload ? 'Загрузить файл' : 'Предложить файл'}</Button
			>
		{/if}
		<span class="spacer"></span>
		<button
			class="circle"
			onclick={togglePin}
			aria-label={subject.pinned ? 'Открепить' : 'Закрепить в боковой панели'}
			title={subject.pinned ? 'Открепить' : 'Закрепить в боковой панели'}
			aria-pressed={subject.pinned}
		>
			{#if subject.pinned}<PinOff size={18} />{:else}<Pin size={18} />{/if}
		</button>
		{#if subject.can.edit}
			<button
				class="circle"
				onclick={() => (editor = true)}
				aria-label="Изменить предмет"
				title="Изменить: название, преподаватель, цвет, иконка, фон, чат"
				><Pencil size={17} /></button
			>
		{/if}
		<Menu items={actions} label="Ещё действия с предметом" />
	</div>

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

	<!-- Вкладки подгружаются при открытии: страница предмета — самая тяжёлая, грузим только нужное. -->
	{#key refresh}
		{#if tab === 'homework'}
			{#await import('$lib/content/HomeworkBoard.svelte')}<Skeleton lines={4} />{:then m}<m.default
					subjectId={subject.id}
					title={false}
					compose={false}
				/>{/await}
		{:else if tab === 'lessons'}
			{#await import('$lib/schedule/SubjectLessons.svelte') then m}<m.default
					subjectId={subject.id}
				/>{/await}
		{:else if tab === 'materials'}
			{#await import('$lib/content/MaterialBrowser.svelte')}<Skeleton
					lines={4}
				/>{:then m}<m.default subjectId={subject.id} subjectName={subject.name} />{/await}
		{:else if tab === 'members'}
			{#await import('$lib/content/MemberList.svelte')}<Skeleton lines={4} />{:then m}<m.default
					groupIds={subject.groups.map((g) => g.id)}
				/>{/await}
		{:else}
			<!-- Новости предмета — вторая вкладка (0.6): код грузится, когда её открыли. -->
			{#await import('$lib/content/NewsFeed.svelte')}<Skeleton lines={4} />{:then m}<m.default
					subjectId={subject.id}
					compose={false}
				/>{/await}
		{/if}
	{/key}

	<!-- Формы добавления грузятся по кнопке. -->
	{#if hwOpen}
		{#await import('$lib/content/HomeworkComposer.svelte') then m}
			<m.default bind:open={hwOpen} subjectId={subject.id} onsaved={() => added('homework')} />
		{/await}
	{/if}
	{#if newsOpen}
		{#await import('$lib/content/NewsComposer.svelte') then m}
			<m.default bind:open={newsOpen} subjectId={subject.id} onsaved={() => added('feed')} />
		{/await}
	{/if}
	{#if fileOpen}
		{#await import('$lib/content/MaterialAdd.svelte') then m}
			<m.default
				bind:open={fileOpen}
				subjectId={subject.id}
				folderId={null}
				suggest={!canUpload}
				onsaved={() => added('materials')}
			/>
		{/await}
	{/if}

	{#if editor}
		{#await import('$lib/content/SubjectEditor.svelte') then m}
			<m.default
				bind:open={editor}
				edit={subject}
				onsaved={(s) => ((subject = s), loadSubjects())}
			/>
		{/await}
	{/if}
	{#if share}
		{#await import('$lib/content/SubjectShare.svelte') then m}
			<m.default bind:open={share} {subject} onsaved={() => (load(), loadSubjects())} />
		{/await}
	{/if}
{/if}

<style>
	.not-mine {
		display: flex;
		align-items: center;
		gap: 12px;
		margin-bottom: var(--s4);
		padding: 12px 16px;
		color: var(--text-2);
	}
	.not-mine p {
		flex: 1;
		min-width: 0;
		margin: 0;
	}
	.not-mine strong {
		color: var(--text);
	}
	/* Место под полосу предметов — сразу, чтобы шапка не прыгала, когда полоса подгрузится. */
	.strip-slot {
		min-height: 108px;
		margin-bottom: var(--s4);
	}
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
	/* Длинное название переносится по словам и ровными строками, без «Груп-па». */
	.hero h1 {
		font-size: clamp(21px, 4.6vw, 28px);
		overflow-wrap: break-word;
		hyphens: manual;
		text-wrap: balance;
	}
	/* Узкий телефон: картинка — полосой сверху, название — во всю ширину, без разрывов слов. */
	@media (max-width: 520px) {
		.hero {
			grid-template-columns: 1fr;
			gap: var(--s3);
		}
		.cover {
			min-height: 112px;
		}
		.hero-info {
			padding: 0 4px 2px;
		}
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
	.chat {
		display: inline-flex;
		align-self: flex-start;
		align-items: center;
		gap: 8px;
		margin-top: 6px;
		padding: 8px 16px;
		border-radius: var(--r-full);
		background: var(--inverse-2);
		color: var(--inverse-text);
		font-weight: 600;
		font-size: 14px;
		text-decoration: none;
	}
	.chat :global(svg) {
		color: var(--tg);
	}
	.chat:hover {
		text-decoration: none;
		filter: brightness(1.15);
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
	.toolbar {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 8px;
		margin: var(--s3) 0 var(--s4);
	}
	.toolbar .circle[aria-pressed='true'] {
		background: var(--accent-soft);
		border-color: var(--accent);
		color: var(--accent);
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
		.subtabs {
			margin-left: 0;
			margin-right: 0;
			padding-left: 2px;
			padding-right: 2px;
		}
	}
	/* Узко — пункты переносятся: разделитель-черта тогда только мешает */
	@media (max-width: 480px) {
		.facts > span + span {
			border-left: 0;
		}
	}
</style>
