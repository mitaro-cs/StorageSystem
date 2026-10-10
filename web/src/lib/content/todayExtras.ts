// Лениво на «Сегодня» (1.0.2): «Первые шаги», «кто на сайте» и меню новостей – одним файлом. Загруженный модуль
// страница помнит на вкладку: при возврате обе части есть с первого кадра и ничего не сдвигают.
export { default as FirstSteps } from './FirstSteps.svelte';
export { default as OnlineNow } from './OnlineNow.svelte';
export { newsActions } from './newsActions';
