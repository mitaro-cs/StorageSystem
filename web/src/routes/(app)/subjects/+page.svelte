<script lang="ts">
	import { Plus, Users, Archive, ArrowRight, EyeOff, Send, Sparkles } from '@lucide/svelte';
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { get, post } from '$lib/api';
	import { can, currentGroup, groups, session } from '$lib/session.svelte';
	import { loadSubjects, subjects } from '$lib/data.svelte';
	import { fly, stagger } from '$lib/motion';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { LinkRequest } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import SubjectArt from '$lib/ui/SubjectArt.svelte';
	import type { Subject } from '$lib/types';
	import { tipOpen } from '$lib/onboarding.svelte';

	let editor = $state(false);
	let wizard = $state(false);
	// «Управление → Семестр» ведёт сюда с ?archive=1 – архив сразу развёрнут.
	let showArchived = $state(page.url.searchParams.has('archive'));
	// «Не мои» свёрнуты: другая подгруппа не мешает, пока сам не развернёшь.
	let showOthers = $state(false);
	let requests = $state<LinkRequest[]>([]);

	const target = $derived(
		currentGroup() ?? groups().find((g) => g.permissions.includes('manage_subjects'))
	);
	const inGroup = $derived(
		subjects.list.filter(
			(s) => session.groupId === null || s.groups.some((g) => g.id === session.groupId)
		)
	);
	const visible = $derived(inGroup.filter((s) => !s.archived && s.mine !== false));
	// «Не мои» – предметы другой подгруппы, которые человек скрыл у себя: внизу, вернуть – в карточке.
	const notMine = $derived(inGroup.filter((s) => !s.archived && s.mine === false));
	// Архив – своим разделом и из той же выборки, что и счётчик: раньше считались архивные всех
	// групп и «не мои», а показывались только свои – «Показать архив (1)» открывал пустоту.
	const archived = $derived(inGroup.filter((s) => s.archived));
	const archivedCount = $derived(archived.length);

	onMount(async () => {
		requests = await get<LinkRequest[]>('/api/link-requests');
	});

	async function decide(r: LinkRequest, accept: boolean) {
		try {
			await post(`/api/link-requests/${r.id}/${accept ? 'accept' : 'reject'}`);
			requests = requests.filter((x) => x.id !== r.id);
			toast(accept ? 'Предмет стал общим' : 'Запрос отклонён', 'ok');
			loadSubjects();
		} catch (e) {
			toastError(e);
		}
	}
</script>

<svelte:head><title>Предметы · Campus</title></svelte:head>

<div class="page-head">
	<h1>Предметы</h1>
	{#if target && can('manage_subjects', target.id)}
		<div class="row wrap head-actions">
			<Button onclick={() => (wizard = true)}><Sparkles size={17} /> Новый семестр</Button>
			<Button variant="primary" onclick={() => (editor = true)}><Plus size={17} /> Предмет</Button>
		</div>
	{/if}
</div>
{#if tipOpen('subjects')}
	{#await import('$lib/tour/Tip.svelte') then m}<m.default
			id="subjects"
			text="Не ходите на какой-то предмет? Откройте его → «…» → «Не мой предмет»: он пропадёт из заданий, расписания и уведомлений."
		/>{/await}
{/if}

{#if requests.length}
	<section class="requests">
		{#each requests as r (r.id)}
			<div class="req card" in:fly>
				<div>
					<strong>{r.subjectName}</strong>
					<p class="muted small">{r.fromGroupName} → {r.toGroupName}: сделать предмет общим</p>
				</div>
				<span class="spacer"></span>
				{#if groups().some((g) => g.id === r.toGroupId && g.permissions.includes('share_subjects')) || session.me?.user.instanceRole === 'admin'}
					<Button size="s" variant="primary" onclick={() => decide(r, true)}>Принять</Button>
					<Button size="s" onclick={() => decide(r, false)}>Отклонить</Button>
				{:else}
					<span class="chip amber">Ждёт ответа</span>
					<Button size="s" variant="ghost" onclick={() => decide(r, false)}>Отозвать</Button>
				{/if}
			</div>
		{/each}
	</section>
{/if}

{#snippet card(s: Subject, i: number)}
	<div class="cell" in:fly={{ y: 10, delay: stagger(i) }}>
		<a
			class="subject card"
			href="/subjects/{s.id}"
			class:archived={s.archived}
			class:other={s.mine === false}
		>
			<span class="cover">
				<SubjectArt
					id={s.id}
					name={s.name}
					color={s.color}
					avatar={s.avatar}
					icon={s.icon}
					class="fill"
				/>
				{#if s.groups.length > 1 || s.archived || s.mine === false}
					<span class="tags">
						{#if s.groups.length > 1}<span class="chip glass"
								><Users size={12} /> общий · {s.groups.length}</span
							>{/if}
						{#if s.archived}<span class="chip glass"><Archive size={12} /> архив</span>{/if}
						{#if s.mine === false}<span class="chip glass"><EyeOff size={12} /> не мой</span>{/if}
					</span>
				{/if}
			</span>
			<span class="foot">
				<span class="text">
					<strong class="name">{s.name}</strong>
					<span class="muted small teacher">{s.teacher || 'Преподаватель не указан'}</span>
				</span>
				<span class="circle ink" aria-hidden="true"><ArrowRight size={18} /></span>
			</span>
		</a>
		{#if s.chatUrl}
			<!-- Рядом с карточкой, а не внутри: ссылка в ссылке недопустима. -->
			<a
				class="chat"
				href={s.chatUrl}
				target="_blank"
				rel="noreferrer"
				aria-label="Чат предмета «{s.name}» в Telegram"
				title="Чат предмета в Telegram"><Send size={16} /></a
			>
		{/if}
	</div>
{/snippet}

{#if visible.length === 0 && notMine.length === 0 && archivedCount === 0}
	<div class="card">
		<Empty
			title="Предметов пока нет"
			text="Староста добавит предметы, и сюда будут попадать задания и материалы."
		/>
	</div>
{:else}
	<div class="grid">
		{#each visible as s, i (s.id)}{@render card(s, i)}{/each}
	</div>
	{#if notMine.length}
		<section class="others">
			<button class="fold" aria-expanded={showOthers} onclick={() => (showOthers = !showOthers)}>
				<EyeOff size={16} />
				<span>Не мои предметы · <span class="num">{notMine.length}</span></span>
				<span class="faint small">{showOthers ? 'Свернуть' : 'Развернуть'}</span>
			</button>
			{#if showOthers}
				<p class="muted small">
					Другая подгруппа: их задания, пары и новости не показываются в общих списках и не приходят
					уведомлениями. Вернуть – «Мой предмет» в меню предмета.
				</p>
				<div class="grid">
					{#each notMine as s, i (s.id)}{@render card(s, i)}{/each}
				</div>
			{/if}
		</section>
	{/if}
{/if}

{#if archivedCount}
	<section class="others">
		<button
			class="fold"
			aria-expanded={showArchived}
			onclick={() => (showArchived = !showArchived)}
		>
			<Archive size={16} />
			<span>Архив · <span class="num">{archivedCount}</span></span>
			<span class="faint small">{showArchived ? 'Свернуть' : 'Развернуть'}</span>
		</button>
		{#if showArchived}
			<!-- По семестрам (0.9.7): лениво, место на странице предметов дорогое. -->
			{#await import('$lib/content/SemesterArchive.svelte')}
				<div class="grid">
					{#each archived as s, i (s.id)}{@render card(s, i)}{/each}
				</div>
			{:then m}
				<m.default
					{archived}
					groupId={target?.id ?? null}
					canManage={!!target && can('manage_subjects', target.id)}
					{card}
				/>
			{/await}
		{/if}
	</section>
{/if}

{#if editor}
	{#await import('$lib/content/SubjectEditor.svelte') then m}
		<m.default bind:open={editor} groupId={target?.id ?? null} onsaved={() => loadSubjects()} />
	{/await}
{/if}

{#if wizard && target}
	{#await import('$lib/content/NewSemester.svelte') then m}
		<m.default bind:open={wizard} group={target} />
	{/await}
{/if}

<style>
	.requests {
		display: flex;
		flex-direction: column;
		gap: var(--s2);
		margin-bottom: var(--s5);
	}
	.req {
		display: flex;
		align-items: center;
		gap: var(--s2);
		flex-wrap: wrap;
	}
	.others {
		display: flex;
		flex-direction: column;
		gap: var(--s2);
		margin-top: var(--s6);
	}
	.fold {
		display: flex;
		align-items: center;
		gap: 10px;
		width: 100%;
		padding: 14px 16px;
		border: 1px solid var(--border);
		border-radius: var(--r-l, 18px);
		background: var(--surface);
		color: var(--text);
		font: inherit;
		font-weight: 650;
		text-align: left;
	}
	.fold > span:nth-child(2) {
		flex: 1;
	}
	.fold:hover {
		border-color: var(--border-strong);
	}
	.others .grid {
		margin-top: var(--s2);
	}
	.subject.other {
		opacity: 0.72;
	}
	/* Карточки как «Upcoming tours»: обложка, название, круглая чёрная стрелка */
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
		gap: var(--s4);
	}
	.cell {
		position: relative;
	}
	.chat {
		position: absolute;
		top: 18px;
		right: 18px;
		z-index: 2;
		display: grid;
		place-items: center;
		width: 36px;
		height: 36px;
		border-radius: 50%;
		background: color-mix(in srgb, var(--surface) 82%, transparent);
		backdrop-filter: blur(10px);
		-webkit-backdrop-filter: blur(10px);
		box-shadow: var(--shadow-1);
		color: var(--tg);
	}
	.chat:hover {
		background: var(--surface);
	}
	.subject {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 12px;
		padding: 8px 8px 12px;
		color: var(--text);
		transition:
			transform var(--dur) var(--ease),
			box-shadow var(--dur) var(--ease);
	}
	.subject:hover {
		text-decoration: none;
		transform: translateY(-2px);
		box-shadow: var(--shadow-2);
	}
	.cover {
		position: relative;
		height: 148px;
	}
	.cover :global(.fill) {
		position: absolute;
		inset: 0;
		border-radius: 18px;
	}
	.tags {
		position: absolute;
		top: 10px;
		left: 10px;
		display: flex;
		gap: 6px;
	}
	.chip.glass {
		background: rgb(255 255 255 / 0.78);
		color: #0d0d0f;
		backdrop-filter: blur(10px);
		-webkit-backdrop-filter: blur(10px);
	}
	.foot {
		display: flex;
		align-items: center;
		gap: var(--s3);
		padding: 0 6px 0 8px;
	}
	.text {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.name {
		font-size: 17px;
		letter-spacing: -0.02em;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.teacher {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.archived {
		opacity: 0.6;
	}
	.head-actions {
		gap: var(--s2);
	}
</style>
