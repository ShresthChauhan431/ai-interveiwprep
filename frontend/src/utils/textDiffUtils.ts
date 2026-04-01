export interface DiffResult {
  text: string;
  type: 'added' | 'removed' | 'unchanged';
}

/**
 * Basic word-level diff implementation to compare user answer to expected answer.
 * Highlights words in the expected answer that the user missed, and words the user said.
 * 
 * Note: A real implementation would use a library like 'diff' (jsdiff),
 * but for simplicity we implement a basic Longest Common Subsequence (LCS) 
 * or simple word matching algorithm here.
 */
export const computeWordDiff = (oldText: string, newText: string): DiffResult[] => {
  // If we don't have both texts, just return the text as unchanged or added
  if (!oldText && !newText) return [];
  if (!oldText) return [{ text: newText, type: 'added' }];
  if (!newText) return [{ text: oldText, type: 'removed' }];

  // Simple tokenization by whitespace, keeping delimiters
  const tokenize = (text: string) => text.split(/(\s+)/).filter(Boolean);
  
  const oldTokens = tokenize(oldText);
  const newTokens = tokenize(newText);
  
  // Very simplistic approach: check if words from oldText exist in newText
  // For a real diff, use a robust library.
  
  // Here we'll do a simple "keyword missing" highlight instead of a full order-preserving diff,
  // since spoken answers rarely match the exact word order of an expected answer.
  
  // Convert newText to lowercase for matching
  const newTextLower = newText.toLowerCase();
  
  const result: DiffResult[] = [];
  
  for (const token of oldTokens) {
    // If it's just whitespace, consider it unchanged
    if (token.trim() === '') {
      result.push({ text: token, type: 'unchanged' });
      continue;
    }
    
    // Remove punctuation for matching
    const cleanToken = token.replace(/[.,/#!$%^&*;:{}=\-_`~()]/g, "").toLowerCase();
    
    if (cleanToken.length < 3) {
      // Don't diff stop words aggressively
      result.push({ text: token, type: 'unchanged' });
    } else if (newTextLower.includes(cleanToken)) {
      result.push({ text: token, type: 'unchanged' });
    } else {
      result.push({ text: token, type: 'removed' }); // user missed this word
    }
  }
  
  return result;
};
