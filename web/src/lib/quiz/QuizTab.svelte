<script lang="ts">
	import { CircleCheck, ClipboardCheck, Clock, Plus, Repeat, Users } from '@lucide/svelte';
	import { del, get } from '$lib/api';
	import { plural } from '$lib/format';
	import { offline } from '$lib/offline/engine';
	import { toast, toastError } from '$lib/toasts.svelte';
	import { ask } from '$lib/ui/ask.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import { scoreText, type QuizDetail, type QuizItem } from './types';

	// Вкладка «Тесты» предмета: список тестов, у ведущих – «Создать тест», черновики и сводка.
	let { subjectId, canEdit }: { subjectId: number; canEdit: boolean } = $props();

	let list = $state<QuizItem[] | null>(null);
	let editorOpen = $state(false);
	let editing = $state<QuizDetail | null>(null);

	async function load() {
		try {
			list = await get<QuizItem[]>(`/api/subjects/${subjectId}/quizzes`);
		} catch (e) {
			list = list ?? [];
			toastError(e);
		}
	}
	$effect(() => {
		void subjectId;
		// Живые обновления: тест добавили или прошли на другом устройстве.
		void offline.version;
		load();
	});

	function create() {
		editing = null;
		editorOpen = true;
	}
	async function edit(q: QuizItem) {
		try {
			editing = await get<QuizDetail>(`/api/quizzes/${q.id}`);
			editorOpen = true;
		} catch (e) {
			toastError(e);
		}
	}
	async function remove(q: QuizItem) {
		const ok = await ask(`Тест «${q.title}» и все результаты по нему удалятся.`, {
			title: 'Удалить тест?',
			ok: 'Удалить',
			danger: true
		});
		if (!ok) return;
		try {
			await del(`/api/quizzes/${q.id}`);
			toast('Тест удалён', 'ok');
			load();
		} catch (e) {
			toastError(e);
		}
	}
	const actions = (q: QuizItem): MenuItem[] => [
		{ label: 'Изменить', onclick: () => edit(q) },
		{ label: 'Удалить', onclick: () => remove(q), danger: true }
	];

	function status(q: QuizItem): string {
		if (q.mine.open) return 'начат – продолжить';
		if (q.mine.used && q.mine.best != null && q.mine.max != null)
			return `пройден: ${scoreText(q.mine.best, q.mine.max)}`;
		if (q.closesAt && q.closesAt < Date.now()) return 'закрыт';
		return 'не пройден';
	}
</script>

{#if canEdit}
	<div class="bar">
		<Button variant="primary" onclick={create}><Plus size={16} /> Создать тест</Button>
	</div>
{/if}

{#if list === null}
	<Skeleton lines={3} />
{:else if list.length === 0}
	<div class="card">
		<Empty
			title="Тестов пока нет"
			text={canEdit
				? 'Создайте тест: вопросы с вариантами или ответом словом, сервер сам проверит и поставит баллы.'
				: 'Когда староста добавит тест по предмету, он появится здесь.'}
		/>
	</div>
{:else}
	<ul class="list quizzes">
		{#each list as q (q.id)}
			<li class="quiz" class:draft={!q.published}>
				<span class="ic"><ClipboardCheck size={20} /></span>
				<span class="main">
					<a class="title" href="/quizzes/{q.id}">{q.title}</a>
					<span class="meta faint small">
						<span>{q.questions} {plural(q.questions, ['вопрос', 'вопроса', 'вопросов'])}</span>
						{#if q.timeLimit}<span class="row"><Clock size={13} /> {q.timeLimit} мин</span>{/if}
						<span class="row"
							><Repeat size={13} />
							{q.attempts === 0
								? 'попыток без ограничения'
								: `${q.attempts} ${plural(q.attempts, ['попытка', 'попытки', 'попыток'])}`}</span
						>
						{#if q.closesAt}<span
								>до {new Date(q.closesAt).toLocaleString('ru-RU', {
									day: 'numeric',
									month: 'long',
									hour: '2-digit',
									minute: '2-digit'
								})}</span
							>{/if}
					</span>
					<span class="state small" class:done={q.mine.used > 0}>
						{#if !q.published}<span class="chip">черновик</span>{/if}
						{#if q.mine.used > 0}<CircleCheck size={14} />{/if}
						{status(q)}
						{#if q.stats}
							<span class="faint row"
								><Users size={13} /> прошли {q.stats.people}{q.stats.average != null
									? ` · в среднем ${Math.round(q.stats.average)} %`
									: ''}</span
							>
						{/if}
					</span>
				</span>
				{#if q.can.edit}<span class="menu"><Menu items={actions(q)} /></span>{/if}
			</li>
		{/each}
	</ul>
{/if}

{#if editorOpen}
	{#await import('./QuizEditor.svelte') then m}
		<m.default bind:open={editorOpen} {subjectId} edit={editing} onsaved={() => load()} />
	{/await}
{/if}

<style>
	.bar {
		display: flex;
		justify-content: flex-end;
		margin-bottom: var(--s3);
	}
	.quizzes {
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.quiz {
		position: relative;
		display: flex;
		align-items: center;
		gap: 14px;
		padding: 14px var(--s4);
		background: var(--surface);
	}
	.quiz + .quiz {
		border-top: 1px solid var(--border);
	}
	.quiz:hover {
		background: color-mix(in srgb, var(--surface-2) 50%, var(--surface));
	}
	.draft .ic {
		opacity: 0.55;
	}
	.ic {
		display: grid;
		place-items: center;
		flex: none;
		width: 40px;
		height: 40px;
		border-radius: 12px;
		background: var(--surface-2);
		color: var(--text-2);
	}
	.main {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.title {
		color: var(--text);
		font-weight: 600;
		font-size: 15.5px;
		overflow-wrap: anywhere;
	}
	/* Вся строка – ссылка на тест; меню поверх. */
	.title::after {
		content: '';
		position: absolute;
		inset: 0;
		z-index: 1;
	}
	.title:hover {
		text-decoration: none;
	}
	.meta,
	.state {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 4px 12px;
	}
	.row {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.state {
		color: var(--text-2);
	}
	.state.done {
		color: var(--ok);
	}
	.menu {
		position: relative;
		z-index: 2;
	}
</style>
