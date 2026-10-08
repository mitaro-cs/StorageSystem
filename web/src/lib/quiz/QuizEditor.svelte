<script lang="ts">
	import { untrack } from 'svelte';
	import {
		ArrowDown,
		ArrowUp,
		Check,
		ClipboardCheck,
		Plus,
		SlidersHorizontal,
		Trash2
	} from '@lucide/svelte';
	import { post, put } from '$lib/api';
	import { toast } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Modal from '$lib/ui/Modal.svelte';
	import FormSection from '$lib/ui/FormSection.svelte';
	import { KIND_LABEL, type QuestionKind, type Question, type QuizDetail } from './types';

	// Тест: название, описание, настройки (время, попытки, показ ответов, до какого дня) и вопросы –
	// с вариантами (один или несколько верных) или ответом словом.
	let {
		open = $bindable(),
		subjectId,
		edit = null,
		onsaved
	}: {
		open: boolean;
		subjectId: number;
		edit?: QuizDetail | null;
		onsaved: (q: QuizDetail) => void;
	} = $props();

	interface Draft {
		key: number;
		kind: QuestionKind;
		text: string;
		options: string[];
		correct: number[];
		accepted: string;
		points: number;
	}

	let key = 0;
	const blank = (kind: QuestionKind = 'single'): Draft => ({
		key: ++key,
		kind,
		text: '',
		options: kind === 'text' ? [] : ['', ''],
		correct: [],
		accepted: '',
		points: 1
	});
	const fromQuestion = (q: Question): Draft => ({
		key: ++key,
		kind: q.kind,
		text: q.text,
		options: q.kind === 'text' ? [] : [...q.options],
		correct: [...q.correct],
		accepted: q.accepted.join('\n'),
		points: q.points ?? 1
	});

	let title = $state('');
	let description = $state('');
	let limited = $state(false);
	let timeLimit = $state(20);
	let attempts = $state(1);
	let showAnswers = $state(true);
	let published = $state(true);
	let closes = $state('');
	let questions = $state<Draft[]>([]);
	let busy = $state(false);
	let error = $state('');

	const p2 = (n: number) => String(n).padStart(2, '0');
	const local = (ms: number) => {
		const d = new Date(ms);
		return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}T${p2(d.getHours())}:${p2(d.getMinutes())}`;
	};

	$effect(() => {
		if (!open) return;
		untrack(() => {
			const q = edit?.quiz;
			title = q?.title ?? '';
			description = q?.description ?? '';
			limited = !!q?.timeLimit;
			timeLimit = q?.timeLimit ?? 20;
			attempts = q?.attempts ?? 1;
			showAnswers = q?.showAnswers ?? true;
			published = q?.published ?? true;
			closes = q?.closesAt ? local(q.closesAt) : '';
			questions = edit?.questions?.length ? edit.questions.map(fromQuestion) : [blank()];
			error = '';
		});
	});

	const dirty = $derived(!!title || questions.some((q) => q.text));

	function setKind(q: Draft, kind: QuestionKind) {
		q.kind = kind;
		if (kind === 'text') q.options = [];
		else if (q.options.length < 2) q.options = ['', ''];
		if (kind === 'single' && q.correct.length > 1) q.correct = q.correct.slice(0, 1);
	}
	function toggleCorrect(q: Draft, i: number) {
		if (q.kind === 'single') q.correct = [i];
		else q.correct = q.correct.includes(i) ? q.correct.filter((x) => x !== i) : [...q.correct, i];
	}
	function removeOption(q: Draft, i: number) {
		q.options.splice(i, 1);
		q.correct = q.correct.filter((x) => x !== i).map((x) => (x > i ? x - 1 : x));
	}
	function move(i: number, by: number) {
		const j = i + by;
		if (j < 0 || j >= questions.length) return;
		const [q] = questions.splice(i, 1);
		questions.splice(j, 0, q);
	}

	async function save(e: SubmitEvent) {
		e.preventDefault();
		error = '';
		const missing = questions.findIndex((q) =>
			q.kind === 'text' ? !q.accepted.trim() : q.correct.length === 0
		);
		if (missing >= 0) {
			error = `Вопрос ${missing + 1}: ${questions[missing].kind === 'text' ? 'впишите верный ответ' : 'отметьте верный вариант'}`;
			return;
		}
		busy = true;
		try {
			const body = {
				title,
				description,
				timeLimit: limited ? timeLimit : null,
				attempts,
				showAnswers,
				published,
				closesAt: closes ? new Date(closes).getTime() : null,
				questions: questions.map((q) => ({
					kind: q.kind,
					text: q.text,
					options: q.options,
					correct: q.correct,
					accepted: q.accepted
						.split('\n')
						.map((s) => s.trim())
						.filter(Boolean),
					points: q.points
				}))
			};
			const saved = edit
				? await put<QuizDetail>(`/api/quizzes/${edit.quiz.id}`, body)
				: await post<QuizDetail>(`/api/subjects/${subjectId}/quizzes`, body);
			toast(edit ? 'Тест сохранён' : published ? 'Тест опубликован' : 'Черновик сохранён', 'ok');
			open = false;
			onsaved(saved);
		} catch (err) {
			error = err instanceof Error ? err.message : 'Не сохранилось';
		} finally {
			busy = false;
		}
	}
</script>

<Modal
	bind:open
	{dirty}
	title={edit ? 'Изменить тест' : 'Новый тест'}
	subtitle="Проверяет сервер – ответы студенты увидят только после своей попытки"
	icon={ClipboardCheck}
	wide
>
	<form id="quiz-form" class="stack form" onsubmit={save}>
		<FormSection title="Тест">
			<div>
				<label class="label" for="q-title">Название</label>
				<input
					id="q-title"
					class="input"
					bind:value={title}
					maxlength="200"
					required
					placeholder="Пределы – проверка по лекции 3"
				/>
			</div>
			<div>
				<label class="label" for="q-desc">Описание</label>
				<textarea
					id="q-desc"
					class="input"
					rows="2"
					bind:value={description}
					maxlength="4000"
					placeholder="Что повторить, можно ли пользоваться конспектом"></textarea>
			</div>
		</FormSection>

		<FormSection title="Настройки" icon={SlidersHorizontal}>
			<div class="grid">
				<label class="check">
					<input type="checkbox" bind:checked={limited} /> Ограничить время
				</label>
				{#if limited}
					<div class="inline">
						<input
							class="input num short"
							type="number"
							min="1"
							max="600"
							bind:value={timeLimit}
							aria-label="Минут на попытку"
						/>
						<span class="faint">мин на попытку</span>
					</div>
				{/if}
				<div class="inline">
					<label class="faint" for="q-attempts">Попыток</label>
					<select id="q-attempts" class="select short" bind:value={attempts}>
						<option value={1}>1</option>
						<option value={2}>2</option>
						<option value={3}>3</option>
						<option value={5}>5</option>
						<option value={0}>без ограничения</option>
					</select>
				</div>
				<div class="inline">
					<label class="faint" for="q-closes">Принимать до</label>
					<input id="q-closes" class="input num" type="datetime-local" bind:value={closes} />
				</div>
				<label class="check">
					<input type="checkbox" bind:checked={showAnswers} /> После попытки показать верные ответы
				</label>
				<label class="check">
					<input type="checkbox" bind:checked={published} /> Открыт для группы (иначе – черновик)
				</label>
			</div>
		</FormSection>

		{#each questions as q, i (q.key)}
			<section class="q card" aria-label="Вопрос {i + 1}">
				<header>
					<strong>Вопрос {i + 1}</strong>
					<span class="tools">
						<Button size="s" variant="ghost" label="Выше" onclick={() => move(i, -1)}
							><ArrowUp size={15} /></Button
						>
						<Button size="s" variant="ghost" label="Ниже" onclick={() => move(i, 1)}
							><ArrowDown size={15} /></Button
						>
						{#if questions.length > 1}
							<Button
								size="s"
								variant="ghost"
								label="Удалить вопрос"
								onclick={() => questions.splice(i, 1)}><Trash2 size={15} /></Button
							>
						{/if}
					</span>
				</header>
				<div class="kinds" role="radiogroup" aria-label="Вид вопроса">
					{#each Object.entries(KIND_LABEL) as [k, label] (k)}
						<button
							type="button"
							role="radio"
							aria-checked={q.kind === k}
							class:on={q.kind === k}
							onclick={() => setKind(q, k as QuestionKind)}>{label}</button
						>
					{/each}
				</div>
				<textarea
					class="input"
					rows="2"
					bind:value={q.text}
					maxlength="2000"
					required
					aria-label="Текст вопроса {i + 1}"
					placeholder="Сколько будет 2 + 2?"></textarea>
				{#if q.kind === 'text'}
					<label class="label" for="acc-{q.key}">Верные ответы – каждый с новой строки</label>
					<textarea
						id="acc-{q.key}"
						class="input"
						rows="2"
						bind:value={q.accepted}
						placeholder="Москва&#10;г. Москва"></textarea>
					<p class="faint small">Регистр, пробелы по краям и «ё» не важны.</p>
				{:else}
					<ul class="options">
						{#each q.options.keys() as j (j)}
							<li>
								<button
									type="button"
									class="mark"
									class:on={q.correct.includes(j)}
									class:multi={q.kind === 'multi'}
									aria-label="Верный вариант {j + 1}"
									aria-pressed={q.correct.includes(j)}
									onclick={() => toggleCorrect(q, j)}
									>{#if q.correct.includes(j)}<Check size={15} strokeWidth={3} />{/if}</button
								>
								<input
									class="input"
									bind:value={q.options[j]}
									maxlength="500"
									required
									aria-label="Вариант {j + 1}"
									placeholder="Вариант {j + 1}"
								/>
								{#if q.options.length > 2}
									<Button
										size="s"
										variant="ghost"
										label="Убрать вариант"
										onclick={() => removeOption(q, j)}><Trash2 size={14} /></Button
									>
								{/if}
							</li>
						{/each}
					</ul>
					{#if q.options.length < 10}
						<Button size="s" variant="ghost" onclick={() => q.options.push('')}
							><Plus size={15} /> Вариант</Button
						>
					{/if}
					<p class="faint small">
						Нажмите кружок у верного {q.kind === 'multi'
							? 'варианта (можно несколько)'
							: 'варианта'}.
					</p>
				{/if}
				<div class="inline">
					<label class="faint" for="pts-{q.key}">Баллов</label>
					<input
						id="pts-{q.key}"
						class="input num short"
						type="number"
						min="1"
						max="100"
						bind:value={q.points}
					/>
				</div>
			</section>
		{/each}
		{#if questions.length < 100}
			<Button onclick={() => questions.push(blank(questions.at(-1)?.kind ?? 'single'))}
				><Plus size={16} /> Добавить вопрос</Button
			>
		{/if}
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		<Button onclick={() => (open = false)}>Отмена</Button>
		<Button variant="primary" type="submit" form="quiz-form" loading={busy}>
			{edit ? 'Сохранить' : published ? 'Опубликовать' : 'Сохранить черновик'}
		</Button>
	{/snippet}
</Modal>

<style>
	.grid {
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.check {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.inline {
		display: flex;
		align-items: center;
		gap: 10px;
		flex-wrap: wrap;
	}
	.short {
		width: 110px;
	}
	.q {
		display: flex;
		flex-direction: column;
		gap: 10px;
		padding: var(--s4);
	}
	.q header {
		display: flex;
		align-items: center;
		justify-content: space-between;
	}
	.tools {
		display: flex;
		gap: 2px;
	}
	.kinds {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}
	.kinds button {
		padding: 6px 12px;
		border: 1px solid var(--border);
		border-radius: 999px;
		background: transparent;
		color: var(--text-2);
		font: inherit;
		font-size: 13.5px;
	}
	.kinds button.on {
		background: var(--text);
		border-color: var(--text);
		color: var(--bg);
	}
	.options {
		display: flex;
		flex-direction: column;
		gap: 8px;
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.options li {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.mark {
		display: grid;
		place-items: center;
		flex: none;
		width: 24px;
		height: 24px;
		padding: 0;
		border: 2px solid var(--border-strong);
		border-radius: 50%;
		background: transparent;
	}
	.mark.multi {
		border-radius: 7px;
	}
	.mark.on {
		border-color: var(--ok);
		background: var(--ok);
		color: #fff;
	}
</style>
