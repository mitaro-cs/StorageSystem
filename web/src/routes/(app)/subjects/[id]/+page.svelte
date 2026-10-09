<script lang="ts">
	import { untrack } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import {
		Archive,
		CalendarDays,
		ClipboardList,
		EyeOff,
		FolderOpen,
		Newspaper,
		Pencil,
		Pin,
		PinOff,
		Plus,
		Send,
		Upload,
		UserRound,
		Users
	} from '@lucide/svelte';
	import { get, put } from '$lib/api';
	import { offline } from '$lib/offline/engine';
	import { loadSubjects, sortedSubjects, subjects } from '$lib/data.svelte';
	import { fly } from '$lib/motion';
	import { can, session } from '$lib/session.svelte';
	import { track } from '$lib/recent';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { Subject } from '$lib/types';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';
	import BackBar from '$lib/ui/BackBar.svelte';
	import SubjectArt from '$lib/ui/SubjectArt.svelte';
	import SubjectGlyph from '$lib/ui/SubjectGlyph.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';

	let subject = $state<Subject | null>(null);
	let missing = $state(false);
	let editor = $state(false);
	let share = $state(false);
	let splitOpen = $state(false);
	// Добавить прямо отсюда: задание, новость, файл – без поиска кнопки во вкладках.
	let hwOpen = $state(false);
	let newsOpen = $state(false);
	let fileOpen = $state(false);
	// После добавления вкладка перезагружается (номер меняется – {#key} пересоздаёт её).
	let refresh = $state(0);
	/** Куда уехал выбор в полосе предметов: новый предмет въезжает с той стороны (1 – справа). */
	let dir = $state(1);

	const id = $derived(Number(page.params.id));
	// Первая вкладка – задания.
	const tab = $derived(page.url.searchParams.get('tab') ?? 'homework');
	// Порядок (0.9.6, просьба владельца): ДЗ, Материалы, Расписание, Новости. «Участники» – только
	// у подгруппы: у обычного предмета это вся группа, вкладка лишняя.
	const subgroup = $derived(!!subject && /№\s*\d|\(\s*\d+\s*\)|\d\s*(под)?гр/i.test(subject.name));
	// Свои преподаватели у видов пар (0.9.6) – под общим.
	const KIND_LABELS = { lecture: 'Лекции', practice: 'Практика', seminar: 'Семинары', lab: 'Лабы' };
	const teachersByKind = $derived(
		Object.entries(subject?.teachers ?? {})
			.filter(([, n]) => n)
			.map(([k, n]) => ({ label: KIND_LABELS[k as keyof typeof KIND_LABELS] ?? k, name: n! }))
	);
	const tabs = $derived(
		[
			{ value: 'homework', label: 'ДЗ', icon: ClipboardList },
			{ value: 'materials', label: 'Материалы', icon: FolderOpen },
			...(subject?.lessons ? [{ value: 'lessons', label: 'Расписание', icon: CalendarDays }] : []),
			{ value: 'feed', label: 'Новости', icon: Newspaper },
			...(subgroup ? [{ value: 'members', label: 'Участники', icon: Users }] : [])
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
		// Предмет изменили на другом устройстве – перечитываем (живые обновления).
		void offline.version;
		untrack(() => {
			// Переход к другому предмету из полосы: страница та же (макет не пересоздаёт её), сразу
			// показываем предмет из списка – без скелета и мигания, свежий приходит следом.
			if (subject && subject.id !== id) {
				const order = others.map((s) => s.id);
				dir = order.indexOf(id) < order.indexOf(subject.id) ? -1 : 1;
				const known = subjects.list.find((s) => s.id === id);
				if (known) subject = known;
				hwOpen = newsOpen = fileOpen = editor = share = splitOpen = false;
			}
			load();
		});
	});

	// Полоса миниатюр предметов – отдельным кусочком (SubjectStrip): место под неё занято сразу.
	// Предметы другой подгруппы («не мой предмет») в полосе не показываем – кроме открытого сейчас.
	const others = $derived(
		sortedSubjects(session.groupId).filter((s) => s.mine !== false || s.id === id)
	);

	async function togglePin() {
		if (!subject) return;
		await put(`/api/subjects/${subject.id}/pinned`, { value: !subject.pinned });
		subject.pinned = !subject.pinned;
		loadSubjects();
	}

	/** «Не мой предмет»: другая подгруппа – убрать из общих списков и уведомлений, или вернуть. */
	async function setMine(mine: boolean) {
		if (!subject) return;
		try {
			await put(`/api/subjects/${subject.id}/mine`, { value: mine });
			subject.mine = mine;
			await loadSubjects();
			toast(
				mine
					? 'Предмет снова ваш: его задания и новости – в общих списках'
					: 'Скрыто: задания, новости и уведомления этого предмета больше не придут',
				'ok'
			);
		} catch (e) {
			toastError(e);
		}
	}

	// Что можно в этом предмете: права – в любой из его групп.
	const inGroups = (perm: Parameters<typeof can>[0]) =>
		!!subject?.groups.some((g) => can(perm, g.id));
	const canHomework = $derived(inGroups('publish_homework'));
	const canNews = $derived(inGroups('publish_news'));
	const canUpload = $derived(inGroups('upload_materials'));
	const canFiles = $derived(canUpload || inGroups('suggest_materials'));

	/** Добавили – открываем вкладку, где это видно, и обновляем её. */
	function added(tab: 'feed' | 'homework' | 'materials') {
		refresh++;
		goto(`/subjects/${id}?tab=${tab}`, {
			replaceState: true,
			noScroll: true,
			keepFocus: true
		});
	}

	// Редкое – в меню «…»: общий предмет, архив, «не мой предмет», подгруппы, удалить.
	const actions = $derived.by((): MenuItem[] => {
		if (!subject) return [];
		const s = subject;
		const out: MenuItem[] = [];
		out.push(
			s.mine === false
				? { label: 'Мой предмет – вернуть в списки', onclick: () => setMine(true) }
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
				onclick: () => (splitOpen = true)
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

<svelte:head><title>{subject?.name ?? 'Предмет'} · Campus</title></svelte:head>

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

	{#key subject.id}<div class="swap" in:fly={{ x: 32 * dir, y: 0, duration: 320 }}>
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

			<!-- Шапка предмета (0.9.4): фон полосой, значок предмета поверх края – виден и с фоном,
	     ниже название, факты и все действия одной строкой. -->
			<header class="hero" style:--c={subject.color}>
				<div class="banner" class:plain={!subject.cover && !subject.avatar}>
					<SubjectArt
						id={subject.id}
						name={subject.name}
						color={subject.color}
						avatar={subject.avatar}
						icon={subject.icon}
						badge={false}
						class="fill"
					/>
				</div>
				<div class="hero-body">
					<span class="emblem"
						><SubjectGlyph
							name={subject.name}
							color={subject.color}
							icon={subject.icon}
							size={56}
						/></span
					>
					<div class="titles">
						<h1>{subject.name}</h1>
						<span class="sub"
							><UserRound size={15} /> {subject.teacher || 'Преподаватель не указан'}</span
						>
						{#if teachersByKind.length}
							<span class="by-kind">
								{#each teachersByKind as t (t.label)}<span><b>{t.label}</b> {t.name}</span>{/each}
							</span>
						{/if}
					</div>
					<div class="facts">
						<span class="fact"
							><Users size={14} /> {subject.groups.map((g) => g.name).join(', ')}</span
						>
						{#if subject.archived}<span class="fact"><Archive size={14} /> в архиве</span>{/if}
						{#if subject.pinned}<span class="fact"><Pin size={14} /> закреплён</span>{/if}
						{#if subject.chatUrl}
							<a class="fact chat" href={subject.chatUrl} target="_blank" rel="noreferrer"
								><Send size={14} /> Чат предмета</a
							>
						{/if}
					</div>
					<!-- Управление предметом – одной строкой: добавить, закрепить, изменить, остальное – в «…». -->
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
				</div>
			</header>

			<nav class="subtabs" aria-label="Разделы предмета" style:--c={subject.color}>
				{#each activeTabs as t (t.label)}
					<a
						href={t.href}
						class:active={t.active}
						aria-current={t.active ? 'page' : undefined}
						data-sveltekit-noscroll
						data-sveltekit-replacestate
						><t.icon size={16} aria-hidden="true" /><span>{t.label}</span></a
					>
				{/each}
			</nav>

			<!-- Вкладки подгружаются при открытии: страница предмета – самая тяжёлая, грузим только нужное. -->
			{#key refresh}
				{#if tab === 'homework'}
					{#await import('$lib/content/HomeworkBoard.svelte')}<Skeleton
							lines={4}
						/>{:then m}<m.default subjectId={subject.id} title={false} compose={false} />{/await}
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
					<!-- Новости предмета – вторая вкладка (0.6): код грузится, когда её открыли. -->
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
			{#if splitOpen}
				{#await import('$lib/content/SubgroupSplit.svelte') then m}
					<m.default bind:open={splitOpen} {subject} />
				{/await}
			{/if}
			{#if share}
				{#await import('$lib/content/SubjectShare.svelte') then m}
					<m.default bind:open={share} {subject} onsaved={() => (load(), loadSubjects())} />
				{/await}
			{/if}
		</div>{/key}
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
	/* Место под полосу предметов – сразу, чтобы шапка не прыгала, когда полоса подгрузится. */
	.strip-slot {
		min-height: 124px;
		margin-bottom: var(--s4);
	}
	.banner :global(.fill) {
		position: absolute;
		inset: 0;
		border-radius: 0;
	}
	.hero {
		position: relative;
		margin-bottom: var(--s4);
		border: 1px solid var(--border);
		border-radius: 26px;
		background: var(--surface);
		box-shadow: var(--shadow-2);
		overflow: hidden;
	}
	.banner {
		position: relative;
		height: clamp(120px, 22vw, 210px);
	}
	/* Без своего фона – полоса цвета предмета пониже, крупный значок и так внизу. */
	.banner.plain {
		height: clamp(84px, 12vw, 120px);
	}
	.banner.plain :global(.glyph) {
		display: none;
	}
	.hero-body {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 12px;
		padding: 0 22px 18px;
	}
	.emblem {
		align-self: flex-start;
		margin-top: -40px;
		padding: 6px;
		border-radius: 24px;
		background: var(--surface);
		box-shadow: 0 6px 20px -6px rgb(0 0 0 / 0.35);
	}
	.emblem :global(.glyph) {
		width: 64px !important;
		height: 64px !important;
		border-radius: 18px;
		background: var(--c) !important;
		color: #fff !important;
	}
	.titles {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
	}
	/* Длинное название переносится по словам и ровными строками, без «Груп-па». */
	.hero h1 {
		font-size: clamp(22px, 4.2vw, 30px);
		letter-spacing: -0.015em;
		overflow-wrap: break-word;
		hyphens: manual;
		text-wrap: balance;
	}
	.sub {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		color: var(--text-2);
	}
	.by-kind {
		display: flex;
		flex-wrap: wrap;
		gap: 4px 14px;
		font-size: 13.5px;
		color: var(--text-2);
	}
	.by-kind b {
		font-weight: 650;
		color: var(--text);
	}
	.facts {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}
	.fact {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		height: 30px;
		padding: 0 12px;
		border-radius: var(--r-full);
		background: var(--surface-2);
		color: var(--text-2);
		font-size: 13px;
		font-weight: 550;
	}
	.chat {
		color: var(--text);
		text-decoration: none;
	}
	.chat :global(svg) {
		color: var(--tg);
	}
	.chat:hover {
		text-decoration: none;
		background: color-mix(in srgb, var(--tg) 14%, var(--surface-2));
	}
	.toolbar {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 8px;
		padding-top: 14px;
		border-top: 1px solid var(--border);
	}
	.toolbar .circle[aria-pressed='true'] {
		background: var(--accent-soft);
		border-color: var(--accent);
		color: var(--accent);
	}
	@media (max-width: 520px) {
		.hero {
			border-radius: 22px;
		}
		.hero-body {
			padding: 0 14px 14px;
		}
		.emblem :global(.glyph) {
			width: 54px !important;
			height: 54px !important;
		}
	}
	/* Вкладки – сегментами на одной дорожке, у каждой значок. */
	.subtabs {
		display: flex;
		gap: 4px;
		margin: 0 0 var(--s4);
		padding: 4px;
		border-radius: var(--r-full);
		background: var(--surface-2);
		overflow-x: auto;
		scrollbar-width: none;
	}
	.subtabs::-webkit-scrollbar {
		display: none;
	}
	.subtabs a {
		flex: 1 0 auto;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 7px;
		height: 40px;
		padding: 0 16px;
		border-radius: var(--r-full);
		color: var(--text-2);
		font-weight: 600;
		font-size: 14.5px;
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
		color: var(--accent-text);
		box-shadow: 0 1px 4px rgb(0 0 0 / 0.12);
	}
	/* Телефон: все вкладки в ширину экрана – значок над подписью; иначе «Тесты» уезжали за край. */
	@media (max-width: 560px) {
		.subtabs {
			border-radius: 18px;
		}
		.subtabs a {
			flex: 1 1 0;
			min-width: 0;
			flex-direction: column;
			gap: 2px;
			height: 52px;
			padding: 0 2px;
			border-radius: 14px;
			font-size: 12px;
		}
		.subtabs a span {
			max-width: 100%;
			overflow: hidden;
			text-overflow: ellipsis;
			white-space: nowrap;
		}
	}
</style>
