# Status
- All generation are done:
    - 4 api models
        - 2 openai
        - 2 anthropic
    - 6 Harness
        - 3 Openai 
        - 3 Anthropic
    - 2 Local models
- Sokrates has been set up
- Divided infer issues into categories instead of just thread-safety warning
- Beginnings of visualisation

# What I want to do this week
- Finish up visualisations
    - What do you want to see?
        - software quality, probability of errors.
        - Histogram X-axis probles, coloumns are count, columns are llms. 

- Qualitatative analysis of a selection of programs
    - Selection metric?
        - Random?
        - Use the correctness score from the decision-1 model?
        - Sampling of different categories.
    - Metrics to analyse
        - Correctness
            - Does it follow the program specification given in the prompt
        - Manual checking of concurrency issues reported by the static analysis tools.
            - ie. false positives
            - Can you check of false negatives? (Or should I?)
        - Observed quirks
            - Random interfaces
            - Dubplicate classes 
